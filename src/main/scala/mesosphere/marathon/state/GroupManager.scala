package mesosphere.marathon.state

import javax.inject.Inject
import mesosphere.marathon.upgrade.{DeploymentPlanRepository, DeploymentPlan}
import mesosphere.marathon.api.v2.{CanaryStrategy, RollingStrategy, Group}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.inject.Singleton
import mesosphere.marathon.MarathonSchedulerService
import org.apache.log4j.Logger
import mesosphere.marathon.tasks.TaskTracker

/**
 * The group manager is the facade for all group related actions.
 * It persists the state of a group and delegates deployments to the UpgradeManager.
 */
class GroupManager @Singleton @Inject() (
  scheduler: MarathonSchedulerService,
  taskTracker: TaskTracker,
  groupRepo: GroupRepository,
  planRepo: DeploymentPlanRepository
) {

  private[this] val log = Logger.getLogger(getClass.getName)

  def list() : Future[Iterable[Group]] = groupRepo.current()

  def group(id:String) : Future[Option[Group]] = groupRepo.group(id)

  def create(group:Group) : Future[Group] = {
    groupRepo.currentVersion(group.id).flatMap {
      case Some(current) =>
        log.warn(s"There is already an group with this id: ${group.id}")
        throw new IllegalArgumentException(s"Can not install group ${group.id}, since there is already a group with this id!")
      case None =>
        log.info(s"Create new Group ${group.id}")
        groupRepo.store(group).flatMap( stored => Future.sequence(stored.apps.map(scheduler.startApp)).map(ignore => stored))
    }
  }

  def upgrade(id:String, group:Group) : Future[Group] = {
    groupRepo.currentVersion(id).flatMap {
      case Some(current) =>
        log.info(s"Update existing Group $id with $group")
        val restart = for {
          storedGroup <- groupRepo.store(group)
          runningTasks = current.apps.map( app => app.id->taskTracker.get(app.id).map(_.getId).toList).toMap.withDefaultValue(Nil)
          plan <- planRepo.store(DeploymentPlan.apply(id, current, storedGroup, runningTasks))
          result <- plan.deploy(scheduler) if result
        } yield storedGroup
        //remove the upgrade plan after the task has been finished
        restart.andThen { case _ => planRepo.expunge(id) }
      case None =>
        log.warn(s"Can not update group $id, since there is no current version!")
        throw new IllegalArgumentException(s"Can not upgrade group $id, since there is no current version!")
    }
  }

  def expunge(id:String) : Future[Boolean] = {
    groupRepo.currentVersion(id).flatMap {
      case Some(current) => Future.sequence(current.apps.map(scheduler.stopApp)).flatMap(_ => groupRepo.expunge(id).map(_.forall(identity)))
      case None => Future.successful(false)
    }
  }
}
