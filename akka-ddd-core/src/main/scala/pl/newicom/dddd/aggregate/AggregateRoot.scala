package pl.newicom.dddd.aggregate

import pl.newicom.dddd.actor.BusinessEntityActorFactory
import pl.newicom.dddd.aggregate.AggregateRootSupport._
import pl.newicom.dddd.aggregate.error._
import pl.newicom.dddd.messaging.{AddressableMessage, Message, MetaData}
import pl.newicom.dddd.messaging.command.CommandMessage
import pl.newicom.dddd.messaging.event.{EventMessage, OfficeEventMessage}
import pl.newicom.dddd.office.LocalOfficeId

import scala.PartialFunction.empty
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

abstract class AggregateRootActorFactory[A <: AggregateRoot[_, _, A]: LocalOfficeId] extends BusinessEntityActorFactory[A] {
  def inactivityTimeout: Duration = 1.minute
}

trait AggregateState[S <: AggregateState[S]] {
  type StateMachine = PartialFunction[DomainEvent, S]
  def apply: StateMachine
  def eventHandlerDefined(e: DomainEvent): Boolean = apply.isDefinedAt(e)
  def initialized: Boolean                         = true
}

trait AggregateBehaviourSupport {
  def error(msg: String): Reject  = reject(msg)
  def reject(msg: String): Reject = Reject(new DomainException(msg))

  def reject(reason: DomainException): Reject = Reject(reason)

  def rejectIf(condition: Boolean, reason: String): RejectConditionally    = new RejectConditionally(condition, reject(reason))
  def rejectIf(condition: Boolean, reject: => Reject): RejectConditionally = new RejectConditionally(condition, reject)
}

trait AggregateBehaviour[E <: DomainEvent, S <: AggregateState[S], C <: Config] extends AggregateState[S] with AggregateBehaviourSupport {

  type HandleQuery              = PartialFunction[Query, Reaction[_]]
  type HandleCommand            = PartialFunction[Command, Reaction[E]]
  type HandleCommandWithContext = CommandHandlerContext[C] => HandleCommand

  def commandHandler: HandleCommandWithContext
  def commandHandlerNoCtx: HandleCommand = commandHandler(null)

  def qHandler: HandleQuery

  implicit def toReaction(e: E): AcceptC[E] =
    AcceptC(Seq(e))
  implicit def toReaction(events: Seq[E]): AcceptC[E] =
    AcceptC(events)

  def reply[R](r: R): AcceptQ[R] = AcceptQ[R](r)
}

trait AggregateActions[E <: DomainEvent, S <: AggregateState[S], C <: Config] extends AggregateBehaviour[E, S, C] {

  case class Actions(cHandler: HandleCommandWithContext, qHandler: HandleQuery = empty, eventHandler: StateMachine = empty) {
    def handleEvent(eh: StateMachine): Actions =
      copy(eventHandler = eh)

    def handleQuery[Q <: Query: ClassTag](hq: Function[Q, Reaction[Q#R]]): Actions = {
      val pf: HandleQuery = { case x: Q => hq(x) }
      copy(qHandler = qHandler.orElse(pf))
    }

    def map[SS <: S](f: SS => S): Actions =
      copy(eventHandler = eventHandler.asInstanceOf[PartialFunction[DomainEvent, SS]].andThen(f))

    def ++(other: Actions): Actions =
      Actions(ctx => cHandler(ctx).orElse(other.cHandler(ctx)), qHandler.orElse(other.qHandler), eventHandler.orElse(other.eventHandler))

    def orElse[SS <: S](other: AggregateActions[E, S, C], f: SS => S = (a: SS) => a): Actions =
      Actions(
        ctx => cHandler(ctx).orElse(other.commandHandlerNoCtx),
        qHandler.orElse(other.qHandler),
        eventHandler.orElse(other.apply.asInstanceOf[PartialFunction[DomainEvent, SS]].andThen(f))
      )

    def orElse(other: Actions): Actions =
      Actions(ctx => cHandler(ctx).orElse(other.cHandler(ctx)), qHandler.orElse(other.qHandler), eventHandler.orElse(other.eventHandler))
  }

  def commandHandler: HandleCommandWithContext =
    actions.cHandler

  def qHandler: HandleQuery =
    actions.qHandler

  def apply: StateMachine =
    actions.eventHandler

  protected def actions: Actions

  def withContext(ctxConsumer: (CommandHandlerContext[C]) => Actions): Actions = {
    Actions(ctx => ctxConsumer(ctx).cHandler(null))
  }

  def handleCommand(hc: HandleCommand): Actions =
    Actions(_ => hc)

  protected def noActions: Actions = Actions(empty)
}

trait Uninitialized[S <: AggregateState[S]] { this: AggregateState[S] =>
  override def initialized = false
}

abstract class AggregateRoot[Event <: DomainEvent, S <: AggregateState[S]: Uninitialized, A <: AggregateRoot[Event, S, A]: LocalOfficeId]
    extends AggregateRootBase
    with CollaborationSupport[Event] {

  type HandlePayload = PartialFunction[Any, Reaction[_]]
  type HandleCommand = CommandHandlerContext[C] => PartialFunction[Command, Reaction[Event]]
  type HandleQuery   = PartialFunction[Query, Reaction[_]]

  def commandHandlerContext(cm: CommandMessage) = CommandHandlerContext(caseRef, config, cm.metadata.getOrElse(MetaData.empty))

  override def officeId: LocalOfficeId[A] = implicitly[LocalOfficeId[A]]
  override def department: String         = officeId.department

  private lazy val sm = new StateManager(onStateChanged = messageProcessed)

  def initialized: Boolean = state.initialized

  def state: S = sm.state

  override def preRestart(reason: Throwable, msgOpt: Option[Any]) {
    reply(Failure(reason))
    super.preRestart(reason, msgOpt)
  }

  override def receiveRecover: Receive = {
    case em: EventMessage => sm.apply(em)
  }

  override def receiveCommand: Receive = {
    case msg: AddressableMessage =>
      safely {
        handlePayload(msg).orElse(handleUnknown).andThen(execute)(msg.payload)
      }
  }

  private def handlePayload(msg: AddressableMessage): HandlePayload = {
    (if (isCommandMsgReceived) {
       handleCommandMessage(commandHandlerContext(msg.asInstanceOf[CommandMessage]))
     } else {
       handleQuery
     }).asInstanceOf[HandlePayload]
  }

  def handleCommandMessage: HandleCommand =
    state.asInstanceOf[AggregateBehaviour[Event, S, C]].commandHandler

  private def handleUnknown: HandlePayload = {
    case payload: Any =>
      val payloadName = payload.getClass.getSimpleName
      Reject(
        if (initialized) {
          if (isCommandMsgReceived)
            new CommandHandlerNotDefined(payloadName)
          else
            new QueryHandlerNotDefined(payloadName)
        } else
          new AggregateRootNotInitialized(officeId.caseName, id, payloadName)
      )
  }

  private def handleQuery: HandleQuery =
    state.asInstanceOf[AggregateBehaviour[Event, S, C]].qHandler

  private def execute(r: Reaction[_]): Unit =
    if (isCommandMsgReceived) {
      executeC(r.asInstanceOf[Reaction[Event]])
    } else {
      executeQ(r)
    }

  private def executeC(r: Reaction[Event]): Unit = r match {
    case c: Collaboration => c.execute(raise)
    case AcceptC(events)  => raise(events)
    case Reject(ex)       => reply(Failure(ex))
  }

  private def executeQ(r: Reaction[_]): Unit = r match {
    case AcceptQ(response) => msgSender ! response
    case Reject(ex)        => msgSender ! Failure(ex)
  }

  private def raise(events: Seq[Event]): Unit = {
    var eventsCount   = 0
    val eventMessages = events.map(toEventMessage).map(_.causedBy(commandMsgReceived))

    val handler =
      sm.eventMessageHandler.andThen { _ =>
        eventsCount += 1
        if (eventsCount == events.size) {
          val oems = eventMessages.map(toOfficeEventMessage)
          reply(Success(oems))
        }
      }

    persistAll(eventMessages.toList)(e => safely(handler(e)))
  }

  private def reply(result: Try[Seq[OfficeEventMessage]], cm: CommandMessage = commandMsgReceived) {
    msgSender ! cm.deliveryReceipt(result.map(successMapper))
  }

  def handleDuplicated(msg: Message): Unit =
    reply(Success(Seq.empty), msg.asInstanceOf[CommandMessage])

  private def safely(f: => Unit): Unit =
    try f catch {
      case ex: Throwable => execute(new Reject(ex))
    }

  private class StateManager(onStateChanged: (EventMessage) => Unit) {
    private var s: S = implicitly[Uninitialized[S]].asInstanceOf[S]

    def state: S = s

    def apply(em: EventMessage): Unit = {
      apply(eventHandler, em)
    }

    def eventMessageHandler: (EventMessage) => EventMessage = em => {
      apply(eventHandler, em)
      em
    }

    private def apply(eventHandler: Function[DomainEvent, S], em: EventMessage): Unit = {
      s = eventHandler(em.event)
      onStateChanged(em)
    }

    private def eventHandler: Function[DomainEvent, S] = event => {
      def eventName   = event.getClass.getSimpleName
      def commandName = commandMsgReceived.command.getClass.getSimpleName
      def caseName    = officeId.caseName
      s match {
        case state if state.eventHandlerDefined(event) =>
          state.apply(event)
        case state if state.initialized =>
          throw new StateTransitionNotDefined(commandName, eventName)
        case _ =>
          throw new AggregateRootNotInitialized(caseName, id, commandName, Some(eventName))
      }
    }

  }

}
