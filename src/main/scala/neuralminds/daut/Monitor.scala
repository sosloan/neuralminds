package neuralminds.daut

sealed trait Step[+S]
final case class Next[S](states: Set[S]) extends Step[S]
final case class Reject(message: String) extends Step[Nothing]
final case class Violation(eventIndex: Long, message: String)

final case class Report[S](violations: Vector[Violation], pending: Set[S]) {
  def passed: Boolean = violations.isEmpty && pending.isEmpty
}

final class Monitor[E, S](
    initial: Set[S],
    accepting: S => Boolean
)(transition: PartialFunction[(S, E), Step[S]]) {
  private var active = initial
  private var failures = Vector.empty[Violation]
  private var index = 0L
  private var observing = false

  def configurations: Set[S] = active
  def violations: Vector[Violation] = failures

  def observe(event: E): Unit = {
    require(!observing, "Monitors do not permit reentrant observation")
    observing = true
    try {
      index += 1
      active = active.flatMap { state =>
        transition.applyOrElse[(S, E), Step[S]]((state, event), _ => Next(Set(state))) match {
          case Next(states) => states
          case Reject(message) =>
            failures :+= Violation(index, message)
            Set.empty[S]
        }
      }
    } finally observing = false
  }

  def finish(): Report[S] = Report(failures, active.filterNot(accepting))
}
