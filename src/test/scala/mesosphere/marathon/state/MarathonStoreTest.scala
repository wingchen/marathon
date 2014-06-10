package mesosphere.marathon.state

import mesosphere.marathon.api.v1.AppDefinition
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.apache.mesos.state.{ InMemoryState, Variable, State }
import java.util.concurrent.{ Future => JFuture, ExecutionException }
import java.lang.{ Boolean => JBoolean }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import mesosphere.marathon.{ MarathonSpec, StorageException }
import scala.language.postfixOps

class MarathonStoreTest extends MarathonSpec {
  test("Fetch") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp")

    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.fetch("testApp")

    verify(state).fetch("app:testApp")
    assert(Some(appDef) == Await.result(res, 5 seconds), "Should return the expected AppDef")
  }

  test("FetchFail") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]

    when(future.get(anyLong, any[TimeUnit])).thenReturn(null)
    when(state.fetch("app:testApp")).thenReturn(future)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.fetch("testApp")

    verify(state).fetch("app:testApp")

    intercept[StorageException] {
      Await.result(res, 5 seconds)
    }
  }

  test("Modify") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp")

    val newAppDef = appDef.copy(id = "newTestApp")
    val newVariable = mock[Variable]
    val newFuture = mock[JFuture[Variable]]

    when(newVariable.value()).thenReturn(newAppDef.toProtoByteArray)
    when(newFuture.get(anyLong, any[TimeUnit])).thenReturn(newVariable)
    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(variable.mutate(any())).thenReturn(newVariable)
    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.store(newVariable)).thenReturn(newFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    assert(Some(newAppDef) == Await.result(res, 5 seconds), "Should return the new AppDef")
    verify(state).fetch("app:testApp")
    verify(state).store(newVariable)
  }

  test("ModifyFail") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp")

    val newAppDef = appDef.copy(id = "newTestApp")
    val newVariable = mock[Variable]
    val newFuture = mock[JFuture[Variable]]

    when(newVariable.value()).thenReturn(newAppDef.toProtoByteArray)
    when(newFuture.get(anyLong, any[TimeUnit])).thenReturn(null)
    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(variable.mutate(any())).thenReturn(newVariable)
    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.store(newVariable)).thenReturn(newFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    intercept[StorageException] {
      Await.result(res, 5 seconds)
    }
  }

  test("Expunge") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val resultFuture = mock[JFuture[JBoolean]]

    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(resultFuture.get(anyLong, any[TimeUnit])).thenReturn(true)
    when(state.expunge(variable)).thenReturn(resultFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())

    val res = store.expunge("testApp")

    assert(Await.result(res, 5 seconds), "Expunging existing variable should return true")
    verify(state).fetch("app:testApp")
    verify(state).expunge(variable)
  }

  test("ExpungeFail") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val resultFuture = mock[JFuture[JBoolean]]

    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(resultFuture.get(anyLong, any[TimeUnit])).thenReturn(null)
    when(state.expunge(variable)).thenReturn(resultFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())

    val res = store.expunge("testApp")

    intercept[StorageException] {
      Await.result(res, 5 seconds)
    }
  }

  test("Names") {
    val state = new InMemoryState

    def populate(key: String, value: Array[Byte]) = {
      val variable = state.fetch(key).get().mutate(value)
      state.store(variable)
    }

    populate("app:foo", Array())
    populate("app:bar", Array())
    populate("no_match", Array())

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.names()

    assert(Seq("foo", "bar") == Await.result(res, 5 seconds).toSeq, "Should return all application keys")
  }

  test("NamesFail") {
    val state = mock[State]
    when(state.names()).thenThrow(classOf[ExecutionException])

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.names()

    assert(Await.result(res, 5 seconds).isEmpty, "Should return empty iterator")
  }

  test("ConcurrentModifications") {
    import mesosphere.util.ThreadPoolContext.context
    val state = new InMemoryState

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())

    Await.ready(store.store("foo", AppDefinition(id = "foo", instances = 0)), 2 seconds)

    def plusOne() = {
      store.modify("foo") { f =>
        val appDef = f()

        appDef.copy(instances = appDef.instances + 1)
      }
    }

    val results = for (_ <- 0 until 1000) yield plusOne()
    val res = Future.sequence(results)

    Await.ready(res, 5 seconds)

    assert(1000 == Await.result(store.fetch("foo"), 5 seconds).map(_.instances)
      .getOrElse(0), "Instances of 'foo' should be set to 1000")
  }
}
