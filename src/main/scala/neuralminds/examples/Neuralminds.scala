package neuralminds.examples

import neuralminds.daut.{Monitor, Next, Reject}
import neuralminds.fprime.{Component, Topology}
import neuralminds.hsm.{Machine, State}
import neuralminds.rules.Rules

object Neuralminds {
  sealed trait Event
  final case class Started(id: Int) extends Event
  final case class Completed(id: Int) extends Event

  final class Worker extends Component("worker") {
    val events = output[Event]("events")
    private val operational = new State("operational")
    private val ready = new State("ready", Some(operational))
    private val busy = new State("busy", Some(operational))
    private val machine = new Machine[String](ready)
    private var job = 0

    machine.when(ready)(_ == "start").goto(busy) { _ => events.emit(Started(job)) }
    machine.when(busy)(_ == "complete").goto(ready) { _ => events.emit(Completed(job)) }

    val commands = input[String]("commands")(command => { machine.handle(command); () })
    def isBusy: Boolean = machine.isIn(busy)
    def nextJob(): Unit = {
      require(!isBusy, "Worker is busy")
      job += 1
      commands.receive("start")
    }
  }

  final class Observer extends Component("observer") {
    // None is a persistent watcher; Some(id) is a pending completion obligation.
    val monitor = new Monitor[Event, Option[Int]](Set(None), _.isEmpty)({
      case (None, Started(id)) => Next(Set(None, Some(id)))
      case (Some(id), Completed(done)) if id == done => Next(Set.empty[Option[Int]])
      case (Some(id), Started(again)) if id == again => Reject(s"Job $id started twice")
    })
    val events = input[Event]("events")(monitor.observe)
  }

  def exercise(jobs: Int): Unit = {
    require(jobs >= 0, "Job count must not be negative")
    val topology = new Topology("neuralminds")
    val worker = topology.add(new Worker)
    val observer = topology.add(new Observer)
    topology.connect(worker.events, observer.events)

    val rules = new Rules
    rules.rule("start")(!worker.isBusy) { worker.nextJob() }
    rules.rule("complete")(worker.isBusy) { worker.commands.receive("complete") }
    rules.invariant("no protocol violations") { observer.monitor.violations.isEmpty }
    val result = rules.run(steps = Math.multiplyExact(jobs, 2), seed = 42L)
    require(result.passed, result.failure.getOrElse("Rule execution failed"))
    require(observer.monitor.finish().passed, "Unfinished jobs")
    println(s"neuralminds: $jobs jobs completed; ${result.trace.size} rule steps; monitor passed")
  }

  def main(args: Array[String]): Unit = exercise(5)
}
