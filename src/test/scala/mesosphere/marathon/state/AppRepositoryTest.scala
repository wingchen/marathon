package mesosphere.marathon.state

import mesosphere.marathon.api.v1.AppDefinition
import org.mockito.Mockito._
import org.mockito.Matchers._
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import org.joda.time.DateTime
import mesosphere.marathon.MarathonSpec

class AppRepositoryTest extends MarathonSpec {
  test("App") {
    val store = mock[MarathonStore[AppDefinition]]
    val timestamp = new Timestamp(DateTime.now())
    val appDef = AppDefinition(id = "testApp", version = timestamp)
    val future = Future.successful(Some(appDef))

    when(store.fetch(s"testApp:${timestamp}")).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.app("testApp", timestamp)

    assert(Some(appDef) == Await.result(res, 5 seconds), "Should return the correct AppDefinition")
    verify(store).fetch(s"testApp:${timestamp}")
  }

  test("Store") {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef = AppDefinition(id = "testApp")
    val future = Future.successful(Some(appDef))
    val versionedKey = s"testApp:${appDef.version}"

    when(store.store(versionedKey, appDef)).thenReturn(future)
    when(store.store("testApp", appDef)).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.store(appDef)

    assert(appDef == Await.result(res, 5 seconds), "Should return the correct AppDefinition")
    verify(store).store(versionedKey, appDef)
    verify(store).store(s"testApp", appDef)
  }

  test("AppIds") {
    val store = mock[MarathonStore[AppDefinition]]
    val future = Future.successful(Seq("app1", "app2", "app1:version", "app2:version").iterator)

    when(store.names()).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.allIds()

    assert(Seq("app1", "app2") == Await.result(res, 5 seconds), "Should return only unversioned names")
    verify(store).names()
  }

  test("Apps") {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef1 = AppDefinition("app1")
    val appDef2 = AppDefinition("app2")
    val appDef1Old = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(1)))
    val appDef2Old = appDef2.copy(version = Timestamp(appDef2.version.dateTime.minusDays(1)))
    val allApps = Seq(appDef1, appDef2, appDef1Old, appDef2Old)

    val future = Future.successful((Seq("app1", "app2") ++ allApps.map(x => s"${x.id}:${x.version}")).toIterator)

    when(store.names()).thenReturn(future)
    when(store.fetch(appDef1.id)).thenReturn(Future.successful(Some(appDef1)))
    when(store.fetch(appDef2.id)).thenReturn(Future.successful(Some(appDef2)))

    val repo = new AppRepository(store)
    val res = repo.apps()

    assert(Seq(appDef1, appDef2) == Await.result(res, 5 seconds), "Should return only current versions")
    verify(store).names()
    verify(store).fetch(appDef1.id)
    verify(store).fetch(appDef2.id)
  }

  test("ListVersions") {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef1 = AppDefinition("app1")
    val version1 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(1)))
    val version2 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(2)))
    val version3 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(3)))
    val appDef2 = AppDefinition("app2")
    val allApps = Seq(appDef1, version1, version2, version3, appDef2)

    val future = Future.successful((Seq("app1", "app2") ++ allApps.map(x => s"${x.id}:${x.version}")).toIterator)

    when(store.names()).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.listVersions(appDef1.id)

    val expected = Seq(appDef1.version, version1.version, version2.version, version3.version)
    assert(expected == Await.result(res, 5 seconds), "Should return all versions of given app")
    verify(store).names()
  }

  test("Expunge") {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef1 = AppDefinition("app1")
    val version1 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(1)))
    val version2 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(2)))
    val version3 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(3)))
    val appDef2 = AppDefinition("app2")
    val allApps = Seq(appDef1, version1, version2, version3, appDef2)

    val future = Future.successful((Seq("app1", "app2") ++ allApps.map(x => s"${x.id}:${x.version}")).toIterator)

    when(store.names()).thenReturn(future)
    when(store.expunge(any())).thenReturn(Future.successful(true))

    val repo = new AppRepository(store)
    val res = Await.result(repo.expunge(appDef1.id), 5 seconds).toSeq

    assert(res.size == 5, "Should expunge all versions")
    assert(res.forall(identity), "Should succeed")

    verify(store).names()
    verify(store).expunge("app1")
    for {
      app <- allApps
      if app.id == "app1"
    } verify(store).expunge(s"${app.id}:${app.version}")
  }
}
