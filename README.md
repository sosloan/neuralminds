# neuralminds

A small Scala system for composing, programming, monitoring, and testing
component-based applications with four interoperable internal, shallow DSLs.
The DSLs are ordinary Scala APIs: guards, actions, data, and assertions use Scala
itself, without code generation or a separate parser.

## Build and run

Requires JDK 17 and sbt (the version is pinned in `project/build.properties`).
The project uses Scala 2.13 and has no third-party runtime or test dependencies.

```sh
sbt compile
sbt test
sbt "runMain neuralminds.examples.Neuralminds"
```

The example prints:

```text
neuralminds: 5 jobs completed; 10 rule steps; monitor passed
```

Tests use Scala assertions; `sbt test` runs the test entry point and fails on an
assertion failure. To run just one group:

```sh
sbt "Test / runMain neuralminds.AllTests hsm"
```

Available groups are `fprime`, `hsm`, `daut`, `rules`, and `integration`.

## F' — composition

`neuralminds.fprime` is inspired by F' (F prime) component-based programming.
It is not a binding to NASA F Prime or a flight-qualified runtime.

Subclass `Component` and declare typed input and output ports. Add components
to a `Topology`, then connect compatible ports:

```scala
import neuralminds.fprime._

class Source extends Component("source") {
  val values = output[Int]("values")
}
class Sink extends Component("sink") {
  val values = input[Int]("values")(n => println(n))
}
val system = new Topology("example")
val source = system.add(new Source)
val sink = system.add(new Sink)
system.connect(source.values, sink.values)
source.values.emit(42)
```

Connections are type-checked by Scala. Component names are unique per topology;
port names are unique per component. Components belong to one topology, and
both ends of a connection must belong to that topology. Duplicate connections
are rejected. Outputs support fan-out in connection order; unconnected outputs
do nothing. Inputs can also be invoked directly with `receive`.

Delivery is synchronous and single-threaded. Reentering an output during its
own delivery is rejected to stop synchronous feedback loops. Handler exceptions
propagate to the caller and stop that delivery; no asynchronous scheduling,
queues, lifecycle management, or cross-thread safety is implied.

## HSM — hierarchical state machines

`neuralminds.hsm` provides `State` and `Machine[E]`. States have an optional
parent and entry/exit callbacks. A machine enters the initial state's entire
ancestry at construction.

```scala
import neuralminds.hsm._

val online = new State("online")
val idle = new State("idle", Some(online))
val busy = new State("busy", Some(online))
val machine = new Machine[String](idle)
machine.when(idle)(_ == "start").goto(busy) { _ => println("working") }
machine.when(online)(_ == "reset").goto(idle) { _ => () }
machine.handle("start")
assert(machine.isIn(online) && machine.current == busy)
```

Dispatch searches the active state before its ancestors; the first matching
transition in declaration order wins. Unhandled events return `false`.
Transitions are external: exits run leaf-first, then the action, then entries
run ancestor-first. Shared ancestors are retained, except when an ancestor is
itself the source or target (including self-transitions). Targets are explicit
states; there are no implicit initial children or history states. Recursive
dispatch is rejected, and callback exceptions propagate without rollback.

## Daut — data automata

`neuralminds.daut.Monitor[E, S]` maintains a set of configurations of any Scala
type `S`, so configurations can carry event data and outstanding obligations.
For each event, a partial function maps each *old* configuration to `Next`
configurations or `Reject(message)`. All branches are checked; a rejecting
branch records a permanent violation even if another branch succeeds.

```scala
import neuralminds.daut._

val monitor = new Monitor[String, Int](Set(0), _ == 2)({
  case (0, "start") => Next(Set(1))
  case (1, "done")  => Next(Set(2))
  case (1, "start") => Reject("already started")
})
monitor.observe("start")
assert(!monitor.finish().passed) // unfinished obligation
monitor.observe("done")
assert(monitor.finish().passed)
```

Unmatched events leave a configuration unchanged. `Next(Set.empty)` terminates
a branch; equal configurations merge. `finish()` returns a snapshot containing
violations (with one-based event indices) and nonaccepting pending states; it
does not close the monitor. Observation is synchronous and nonreentrant.

## Rules — rule-based testing

`neuralminds.rules.Rules` selects an enabled action with a seeded pseudorandom
generator. Preconditions are reevaluated at each step. Invariants are checked
before the first action and after every action.

```scala
import neuralminds.rules._

var count = 0
val rules = new Rules
rules.rule("increment")(count < 3) { count += 1 }
rules.invariant("bounded")(count <= 3)
val result = rules.run(steps = 3, seed = 42L)
assert(result.passed)
```

Results contain the attempted rule-name trace and the first failure.
Failed invariants, no enabled rules before the requested step count, and
nonfatal exceptions are reported as failures. Zero steps still checks initial
invariants. The same seed replays selection when starting from the same state
with deterministic guards/actions; a run does not reset your application.

## Using the four DSLs together

`src/main/scala/neuralminds/examples/Neuralminds.scala` is a complete example.
A worker component handles commands with an HSM and emits job events through
F' ports. An observer component feeds those events into a Daut monitor, which
tracks job IDs and requires every started job to complete. Rules drive the
worker and check monitoring invariants, followed by a final obligation check.
Each DSL can also be used independently.