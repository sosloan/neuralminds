package neuralminds

import neuralminds.daut.{Monitor, Next, Reject}
import neuralminds.examples.Neuralminds
import neuralminds.fprime.{Component, Topology}
import neuralminds.hsm.{Machine, State}
import neuralminds.rules.Rules
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

object AllTests {
  private def intercept[E <: Throwable: ClassTag](body: => Unit): Unit = {
    val expected = implicitly[ClassTag[E]].runtimeClass
    try {
      body
      throw new AssertionError(s"Expected ${expected.getSimpleName}")
    } catch {
      case error: Throwable if expected.isInstance(error) => ()
    }
  }

  private def fprime(): Unit = {
    val received = ArrayBuffer.empty[String]
    class Source(name: String) extends Component(name) {
      val out = output[Int]("out")
    }
    class Sink(name: String) extends Component(name) {
      val in = input[Int]("in")(value => { received += s"$name:$value"; () })
    }

    val topology = new Topology("test")
    val source = topology.add(new Source("source"))
    val first = topology.add(new Sink("first"))
    val second = topology.add(new Sink("second"))
    source.out.emit(0)
    assert(received.isEmpty)
    topology.connect(source.out, first.in)
    topology.connect(source.out, second.in)
    source.out.emit(7)
    assert(received.toVector == Vector("first:7", "second:7"))
    first.in.receive(8)
    assert(received.last == "first:8")
    intercept[IllegalArgumentException] { topology.connect(source.out, first.in) }
    intercept[IllegalArgumentException] { topology.add(new Source("source")) }
    intercept[IllegalArgumentException] { new Topology("other").add(source) }
    val foreign = new Topology("foreign").add(new Sink("foreign"))
    intercept[IllegalArgumentException] { topology.connect(source.out, foreign.in) }
    intercept[IllegalArgumentException] { topology.connect(source.out, new Sink("unbound").in) }
    intercept[IllegalArgumentException] { new Source(" ") }
    intercept[IllegalArgumentException] { new Topology("") }
    intercept[IllegalArgumentException] {
      new Component("duplicate") {
        output[Int]("port")
        input[Int]("port")(_ => ())
      }
    }

    var feedback = true
    class Loop extends Component("loop") {
      val out = output[Int]("out")
      val in = input[Int]("in")(value => if (feedback) out.emit(value))
    }
    val loop = topology.add(new Loop)
    topology.connect(loop.out, loop.in)
    intercept[IllegalArgumentException] { loop.out.emit(1) }
    feedback = false
    loop.out.emit(2)

    var throwing = true
    class Fragile extends Component("fragile") {
      val out = output[Int]("out")
      val in = input[Int]("in")(_ => if (throwing) throw new IllegalStateException("handler"))
    }
    val fragile = topology.add(new Fragile)
    topology.connect(fragile.out, fragile.in)
    intercept[IllegalStateException] { fragile.out.emit(1) }
    throwing = false
    fragile.out.emit(2)
  }

  private def hsm(): Unit = {
    val trace = ArrayBuffer.empty[String]
    def state(name: String, parent: Option[State] = None): State =
      new State(name, parent, () => { trace += s"+$name"; () }, () => { trace += s"-$name"; () })

    val root = state("root")
    val online = state("online", Some(root))
    val idle = state("idle", Some(online))
    val busy = state("busy", Some(online))
    val off = state("off", Some(root))
    val machine = new Machine[String](idle)
    assert(trace.toVector == Vector("+root", "+online", "+idle"))
    assert(machine.current == idle && machine.isIn(root) && !machine.isIn(busy))
    machine.when(idle)(_ == "go").goto(busy) { _ => trace += "go"; () }
    machine.when(idle)(_ == "go").goto(off) { _ => sys.error("second transition won") }
    machine.when(online)(_ == "go").goto(off) { _ => sys.error("ancestor won") }
    machine.when(online)(_ == "reset").goto(idle) { _ => trace += "reset"; () }
    machine.when(busy)(_ == "up").goto(online) { _ => trace += "up"; () }
    machine.when(online)(_ == "self").goto(online) { _ => trace += "self"; () }
    machine.when(root)(_ == "off").goto(off) { _ => trace += "off"; () }

    trace.clear()
    assert(!machine.handle("unknown") && trace.isEmpty)
    assert(machine.handle("go"))
    assert(trace.toVector == Vector("-idle", "go", "+busy"))
    trace.clear()
    assert(machine.handle("reset"))
    assert(trace.toVector == Vector("-busy", "-online", "reset", "+online", "+idle"))
    assert(machine.handle("go"))
    trace.clear()
    assert(machine.handle("up") && machine.current == online)
    assert(trace.toVector == Vector("-busy", "-online", "up", "+online"))
    trace.clear()
    assert(machine.handle("self"))
    assert(trace.toVector == Vector("-online", "self", "+online"))
    trace.clear()
    assert(machine.handle("off"))
    assert(trace.toVector == Vector("-online", "-root", "off", "+root", "+off"))

    machine.when(off)(_ == "recursive").goto(idle) { event => machine.handle(event); () }
    intercept[IllegalArgumentException] { machine.handle("recursive") }
    assert(!machine.handle("unknown"))
    intercept[IllegalArgumentException] { new State("") }
  }

  private def daut(): Unit = {
    sealed trait Config
    case object Watch extends Config
    final case class Pending(id: Int) extends Config
    val monitor = new Monitor[Neuralminds.Event, Config](Set(Watch), _ == Watch)({
      case (Watch, Neuralminds.Started(id)) => Next(Set(Watch, Pending(id)))
      case (Pending(id), Neuralminds.Completed(done)) if id == done => Next(Set.empty[Config])
      case (Pending(id), Neuralminds.Started(again)) if id == again => Reject(s"duplicate $id")
    })
    assert(monitor.finish().passed)
    monitor.observe(Neuralminds.Started(1))
    monitor.observe(Neuralminds.Started(2))
    assert(monitor.configurations == Set(Watch, Pending(1), Pending(2)))
    val snapshot = monitor.finish()
    assert(!snapshot.passed && snapshot.pending == Set(Pending(1), Pending(2)))
    monitor.observe(Neuralminds.Completed(9))
    assert(monitor.configurations == Set(Watch, Pending(1), Pending(2)))
    monitor.observe(Neuralminds.Completed(1))
    assert(monitor.finish().pending == Set(Pending(2)))
    monitor.observe(Neuralminds.Completed(2))
    assert(monitor.finish().passed)
    assert(snapshot.pending.size == 2)
    monitor.observe(Neuralminds.Started(3))
    monitor.observe(Neuralminds.Started(3))
    assert(monitor.violations.size == 1)
    assert(monitor.violations.head.eventIndex == 7L)
    assert(monitor.violations.head.message == "duplicate 3")
    monitor.observe(Neuralminds.Completed(3))
    assert(!monitor.finish().passed)

    val branching = new Monitor[Int, Int](Set(0), _ == 3)({
      case (0, 1) => Next(Set(1, 2))
      case (1, 1) => Reject("must wait for next event")
      case (1, 2) => Next(Set(3))
      case (2, 2) => Next(Set(3))
    })
    branching.observe(1)
    assert(branching.configurations == Set(1, 2) && branching.violations.isEmpty)
    branching.observe(2)
    assert(branching.configurations == Set(3) && branching.finish().passed)

    val rejectingBranch = new Monitor[Int, Int](Set(1, 2), _ => true)({
      case (1, _) => Reject("one branch failed")
      case (2, _) => Next(Set(3))
    })
    rejectingBranch.observe(0)
    assert(rejectingBranch.configurations == Set(3) && !rejectingBranch.finish().passed)

    val terminal = new Monitor[Int, Int](Set(0), _ => false)({
      case (_, 0) => Next(Set.empty[Int])
    })
    terminal.observe(0)
    terminal.observe(1)
    assert(terminal.finish().passed)

    var throwOnce = true
    val exceptional = new Monitor[Int, Int](Set(0), _ => true)({
      case (state, _) =>
        if (throwOnce) throw new IllegalStateException("transition")
        Next(Set(state))
    })
    intercept[IllegalStateException] { exceptional.observe(0) }
    throwOnce = false
    exceptional.observe(0)
    assert(exceptional.finish().passed)

    lazy val recursive: Monitor[Int, Int] = new Monitor[Int, Int](Set(0), _ => true)({
      case (state, event) =>
        recursive.observe(event)
        Next(Set(state))
    })
    intercept[IllegalArgumentException] { recursive.observe(0) }
  }

  private def rules(): Unit = {
    def replay(seed: Long): Vector[String] = {
      val rules = new Rules
      rules.rule("a")(true) { () }
      rules.rule("b")(true) { () }
      rules.run(100, seed).trace
    }
    assert(replay(42) == replay(42))
    assert(replay(42) != replay(43))

    var count = 0
    val rules = new Rules
    rules.rule("increment")(count < 2) { count += 1 }
    rules.invariant("bounded")(count <= 2)
    val result = rules.run(2)
    assert(result.passed && result.trace == Vector("increment", "increment") && count == 2)
    assert(rules.run(1).failure.contains("No enabled rules"))
    assert(rules.run(0).passed)
    intercept[IllegalArgumentException] { rules.run(-1) }
    intercept[IllegalArgumentException] { rules.rule("increment")(true) { () } }
    intercept[IllegalArgumentException] { rules.invariant("bounded")(true) }
    intercept[IllegalArgumentException] { rules.rule(" ")(true) { () } }

    val initiallyInvalid = new Rules
    initiallyInvalid.invariant("initial")(false)
    assert(initiallyInvalid.run(0).failure.contains("Invariant failed: initial"))
    var valid = true
    val becomesInvalid = new Rules
    becomesInvalid.rule("break")(true) { valid = false }
    becomesInvalid.invariant("valid")(valid)
    val failed = becomesInvalid.run(5)
    assert(failed.trace == Vector("break") && failed.failure.contains("Invariant failed: valid"))

    val throwing = new Rules
    throwing.rule("throw")(true) { throw new IllegalStateException("action") }
    val thrown = throwing.run(1)
    assert(thrown.trace == Vector("throw"))
    assert(thrown.failure.exists(_.contains("Exception in rule throw: IllegalStateException: action")))
    val badGuard = new Rules
    badGuard.rule("guard")(throw new IllegalStateException("guard")) { () }
    assert(badGuard.run(1).failure.exists(_.contains("rule preconditions")))
    val badInvariant = new Rules
    badInvariant.invariant("throws")(throw new IllegalStateException("invariant"))
    assert(badInvariant.run(0).failure.exists(_.contains("initial invariants")))
    assert(!new Rules().run(1).passed)
  }

  private def integration(): Unit = {
    Neuralminds.exercise(0)
    Neuralminds.exercise(5)
    intercept[IllegalArgumentException] { Neuralminds.exercise(-1) }
    val topology = new Topology("integration")
    val worker = topology.add(new Neuralminds.Worker)
    val observer = topology.add(new Neuralminds.Observer)
    topology.connect(worker.events, observer.events)
    worker.nextJob()
    assert(worker.isBusy && !observer.monitor.finish().passed)
    worker.commands.receive("start")
    assert(observer.monitor.violations.isEmpty)
    intercept[IllegalArgumentException] { worker.nextJob() }
    worker.commands.receive("complete")
    assert(!worker.isBusy && observer.monitor.finish().passed)
    worker.nextJob()
    assert(observer.monitor.configurations.contains(Some(2)))
    worker.commands.receive("complete")
    assert(observer.monitor.finish().passed)
  }

  def main(args: Array[String]): Unit = {
    val suites = Vector[(String, () => Unit)](
      "fprime" -> (() => fprime()),
      "hsm" -> (() => hsm()),
      "daut" -> (() => daut()),
      "rules" -> (() => rules()),
      "integration" -> (() => integration())
    )
    require(args.forall(name => suites.exists(_._1 == name)), "Unknown test group")
    suites.filter { case (name, _) => args.isEmpty || args.contains(name) }.foreach {
      case (name, run) =>
        run()
        println(s"$name tests passed")
    }
  }
}
