package neuralminds.rules

import scala.util.Random
import scala.util.control.NonFatal

final case class RunResult(trace: Vector[String], failure: Option[String]) {
  def passed: Boolean = failure.isEmpty
}

final class Rules {
  private final class Rule(val name: String, val enabled: () => Boolean, val action: () => Unit)
  private var rules = Vector.empty[Rule]
  private var invariants = Vector.empty[(String, () => Boolean)]

  def rule(name: String)(enabled: => Boolean)(action: => Unit): Unit = {
    require(name.trim.nonEmpty && !rules.exists(_.name == name), "Rule names must be nonempty and unique")
    rules :+= new Rule(name, () => enabled, () => action)
  }

  def invariant(name: String)(check: => Boolean): Unit = {
    require(
      name.trim.nonEmpty && !invariants.exists(_._1 == name),
      "Invariant names must be nonempty and unique"
    )
    invariants :+= name -> (() => check)
  }

  def run(steps: Int, seed: Long = 0L): RunResult = {
    require(steps >= 0, "Step count must not be negative")
    val random = new Random(seed)
    var trace = Vector.empty[String]
    var context = "initial invariants"

    def failedInvariant(): Option[String] =
      invariants.collectFirst { case (name, check) if !check() => s"Invariant failed: $name" }

    try {
      var failure = failedInvariant()
      while (trace.size < steps && failure.isEmpty) {
        context = "rule preconditions"
        val enabled = rules.filter(_.enabled())
        if (enabled.isEmpty) failure = Some("No enabled rules")
        else {
          val selected = enabled(random.nextInt(enabled.size))
          context = s"rule ${selected.name}"
          trace :+= selected.name
          selected.action()
          context = s"invariants after ${selected.name}"
          failure = failedInvariant()
        }
      }
      RunResult(trace, failure)
    } catch {
      case NonFatal(error) =>
        RunResult(trace, Some(s"Exception in $context: ${error.getClass.getSimpleName}: ${error.getMessage}"))
    }
  }
}
