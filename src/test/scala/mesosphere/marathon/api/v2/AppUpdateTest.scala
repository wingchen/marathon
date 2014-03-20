package mesosphere.marathon.api.v2

import org.junit.Test
import org.junit.Assert._
import javax.validation.Validation
import scala.collection.JavaConverters._

class AppUpdateTest {

  @Test
  def testValidation() {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def should(assertion: (Boolean) => Unit, update: AppUpdate, path: String, template: String) = {
      val violations = validator.validate(update).asScala
      assertion(violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    def shouldViolate(update: AppUpdate, path: String, template: String) =
      should(assertTrue, update, path, template)

    def shouldNotViolate(update: AppUpdate, path: String, template: String) =
      should(assertFalse, update, path, template)

    val update = AppUpdate()

    shouldViolate(
      update.copy(ports = Some(Seq(9000, 8080, 9000))),
      "ports",
      "Elements must be unique"
    )

  }

  @Test
  def testDefaultValues() {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)

    val json = """{ "instances": 3 }"""

    for (i <- (0 until 1024 * 1024)) {
      val readResult = mapper.readValue(json, classOf[AppUpdate])
      assertTrue(readResult.instances == Some(3))
      assertTrue(readResult.uris == None)
    }

  }

}
