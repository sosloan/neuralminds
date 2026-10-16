package neuralminds.fprime

import scala.collection.mutable

abstract class Component(val name: String) {
  require(name.trim.nonEmpty, "Component names must not be empty")

  private val portNames = mutable.Set.empty[String]
  private[fprime] var system: Option[Topology] = None

  private def register(name: String): Unit = {
    require(name.trim.nonEmpty, "Port names must not be empty")
    require(portNames.add(name), s"Duplicate port: ${this.name}.$name")
  }

  protected final def input[A](name: String)(handler: A => Unit): Input[A] = {
    register(name)
    new Input(this, name, handler)
  }

  protected final def output[A](name: String): Output[A] = {
    register(name)
    new Output(this, name)
  }
}

final class Input[A] private[fprime] (
    val component: Component,
    val name: String,
    handler: A => Unit
) {
  def receive(value: A): Unit = handler(value)
}

final class Output[A] private[fprime] (val component: Component, val name: String) {
  private var receivers = Vector.empty[Input[A]]
  private var emitting = false

  private[fprime] def connect(input: Input[A]): Unit = {
    require(!receivers.contains(input), "Ports are already connected")
    receivers :+= input
  }

  def emit(value: A): Unit = {
    require(!emitting, s"Synchronous feedback through ${component.name}.$name")
    emitting = true
    try receivers.foreach(_.receive(value))
    finally emitting = false
  }
}

final class Topology(val name: String) {
  require(name.trim.nonEmpty, "Topology names must not be empty")
  private val components = mutable.LinkedHashMap.empty[String, Component]

  def add[C <: Component](component: C): C = {
    require(component.system.isEmpty, "Component already belongs to a topology")
    require(!components.contains(component.name), s"Duplicate component: ${component.name}")
    components(component.name) = component
    component.system = Some(this)
    component
  }

  def connect[A](output: Output[A], input: Input[A]): Unit = {
    require(
      output.component.system.contains(this) && input.component.system.contains(this),
      "Both ports must belong to this topology"
    )
    output.connect(input)
  }
}
