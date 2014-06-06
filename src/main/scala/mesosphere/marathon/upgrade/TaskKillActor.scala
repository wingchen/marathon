package mesosphere.marathon.upgrade

import org.apache.mesos.SchedulerDriver
import akka.event.EventStream
import mesosphere.marathon.Protos.MarathonTask
import akka.actor.{Actor, ActorLogging}
import scala.concurrent.Promise
import mesosphere.marathon.event.MesosStatusUpdateEvent
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.TaskUpgradeCancelledException
import scala.collection.mutable

class TaskKillActor(
  driver: SchedulerDriver,
  eventBus: EventStream,
  tasksToKill: Set[MarathonTask],
  promise: Promise[Boolean]
) extends Actor with ActorLogging {

  val idsToKill = tasksToKill.map(_.getId).to[mutable.Set]

  eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])

  override def preStart(): Unit = {
    log.info(s"Killing ${tasksToKill.size} instances")
    for (task <- tasksToKill)
      driver.killTask(taskId(task.getId))
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCancelledException(
          "The task upgrade has been cancelled"))
  }

  def receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_KILLED", _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill.remove(taskId)
      log.info(s"Task $taskId has been killed. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case MesosStatusUpdateEvent(_, taskId, "TASK_LOST", _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill.remove(taskId)
      log.warning(s"Task $taskId should have been killed but was lost, removing it from the list. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case x: MesosStatusUpdateEvent => log.debug(s"Received $x")
  }

  def checkFinished(): Unit =
    if (idsToKill.size == 0) {
      log.info("Successfully killed all the tasks")
      promise.success(true)
      context.stop(self)
    }

  private def taskId(id: String) = TaskID.newBuilder().setValue(id).build()
}
