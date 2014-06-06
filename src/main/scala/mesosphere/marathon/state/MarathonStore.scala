package mesosphere.marathon.state

import com.google.protobuf.InvalidProtocolBufferException
import org.apache.mesos.state.State
import scala.collection.JavaConverters._
import scala.concurrent._
import mesosphere.marathon.StorageException
import mesosphere.util.LockManager
import mesosphere.util.{ ThreadPoolContext, BackToTheFuture }

class MarathonStore[S <: MarathonState[_, S]](state: State,
                                              newState: () => S,
                                              prefix: String = "app:",
                                              implicit val timeout: BackToTheFuture.Timeout = BackToTheFuture.Implicits.defaultTimeout) extends PersistenceStore[S] {

  import ThreadPoolContext.context
  import BackToTheFuture.futureToFutureOption

  private [this] val locks = LockManager()

  def fetch(key: String): Future[Option[S]] = {
    state.fetch(prefix + key) map {
      case Some(variable) => stateFromBytes(variable.value)
      case None           => throw new StorageException(s"Failed to read $key")
    }
  }

  def modify(key: String)(f: (() => S) => S): Future[Option[S]] = {
    val lock = locks.get(key)
    lock.acquire()

    val res = state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        val deserialize = () => stateFromBytes(variable.value).getOrElse(newState())
        state.store(variable.mutate(f(deserialize).toProtoByteArray)) map {
          case Some(newVar) => stateFromBytes(newVar.value)
          case None         => throw new StorageException(s"Failed to store $key")
        }
      case None => throw new StorageException(s"Failed to read $key")
    }

    res onComplete { _ =>
      lock.release()
    }

    res
  }

  def expunge(key: String): Future[Boolean] = {
    val lock = locks.get(key)
    lock.acquire()

    val res = state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        state.expunge(variable) map {
          case Some(b) => b.booleanValue()
          case None    => throw new StorageException(s"Failed to expunge $key")
        }

      case None => throw new StorageException(s"Failed to read $key")
    }

    res onComplete { _ =>
      lock.release()
    }

    res
  }

  def names(): Future[Iterator[String]] = {
    // TODO use implicit conversion after it has been merged
    future {
      try {
        state.names().get.asScala.collect {
          case name if name startsWith prefix =>
            name.replaceFirst(prefix, "")
        }
      }
      catch {
        // Thrown when node doesn't exist
        case e: ExecutionException => Seq().iterator
      }
    }
  }

  private def stateFromBytes(bytes: Array[Byte]): Option[S] = {
    try {
      Some(newState().mergeFromProto(bytes))
    }
    catch {
      case e: InvalidProtocolBufferException => None
    }
  }
}
