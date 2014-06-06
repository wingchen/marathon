package mesosphere.marathon.upgrade

import mesosphere.marathon.tasks.TaskQueue
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Promise
import akka.actor.{ActorLogging, Actor}
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.{TaskUpgradeCancelledException, TaskFailedException}

class TaskStartActor(
  taskQueue: TaskQueue,
  eventBus: EventStream,
  app: AppDefinition,
  promise: Promise[Boolean]
) extends Actor with ActorLogging {

  val nrToStart: Int = app.instances
  var running: Int = 0
  val AppID = app.id
  val Version = app.version.toString

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    for (_ <- 0 until nrToStart) taskQueue.add(app)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCancelledException(
          "The task upgrade has been cancelled"))
  }

  def receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_RUNNING", AppID, _, _, Version, _, _) =>
      running += 1
      log.info(s"Task $taskId is now running. Waiting for ${nrToStart - running} more tasks.")
      if (running == nrToStart) {
        promise.success(true)
        context.stop(self)
      }

    case MesosStatusUpdateEvent(_, _, "TASK_FAILED", AppID, _, _, Version, _, _) =>
      promise.failure(new TaskFailedException("Task failed during start"))
      context.stop(self)

    case x: MesosStatusUpdateEvent => log.debug(s"Received $x")
  }
}
