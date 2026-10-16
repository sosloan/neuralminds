package neuralminds.hsm

final class State(
    val name: String,
    val parent: Option[State] = None,
    val onEntry: () => Unit = () => (),
    val onExit: () => Unit = () => ()
) {
  require(name.trim.nonEmpty, "State names must not be empty")
  private[hsm] def path: List[State] = parent.fold(List(this))(_.path :+ this)
}

final class Machine[E](initial: State) {
  private final class Transition(
      val source: State,
      val guard: E => Boolean,
      val target: State,
      val action: E => Unit
  )

  private var transitions = Vector.empty[Transition]
  private var active = initial
  private var dispatching = false
  initial.path.foreach(_.onEntry())

  def current: State = active
  def isIn(state: State): Boolean = active.path.contains(state)

  def when(source: State)(guard: E => Boolean): TransitionBuilder =
    new TransitionBuilder(source, guard)

  final class TransitionBuilder private[hsm] (source: State, guard: E => Boolean) {
    def goto(target: State)(action: E => Unit): Unit =
      transitions :+= new Transition(source, guard, target, action)
  }

  def handle(event: E): Boolean = {
    require(!dispatching, "State machines do not permit reentrant event handling")
    dispatching = true
    try {
      val selected = active.path.reverseIterator
        .flatMap(state => transitions.iterator.filter(t => t.source == state && t.guard(event)))
        .take(1)
        .toList
        .headOption
      selected match {
        case None => false
        case Some(transition) =>
          val from = active.path
          val to = transition.target.path
          val shared = from.zip(to).takeWhile { case (a, b) => a == b }.size
          // External transitions reenter either endpoint if it is the common ancestor.
          val retained = math.min(shared, math.min(transition.source.path.size - 1, to.size - 1))
          from.drop(retained).reverse.foreach(_.onExit())
          transition.action(event)
          active = transition.target
          to.drop(retained).foreach(_.onEntry())
          true
      }
    } finally dispatching = false
  }
}
