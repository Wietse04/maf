trait Semantics[Exp, Abs, Addr] {
  implicit def abs : AbstractValue[Abs]
  implicit def absi : AbstractInjection[Abs]
  implicit def addr : Address[Addr]
  implicit def addri : AddressInjection[Addr]
  implicit def exp : Expression[Exp]
  def stepEval(e: Exp, ρ: Environment[Addr], σ: Store[Addr, Abs], a: Addr): Set[Action[Exp, Abs, Addr]]
  def stepKont(v: Abs, σ: Store[Addr, Abs], κ: Kontinuation): Set[Action[Exp, Abs, Addr]]
}

abstract class Action[Exp : Expression, Abs : AbstractValue, Addr : Address]
case class ActionReachedValue[Exp : Expression, Abs : AbstractValue, Addr : Address](v: Abs, σ: Store[Addr, Abs], a: Addr) extends Action[Exp, Abs, Addr]
case class ActionPush[Exp : Expression, Abs : AbstractValue, Addr : Address](e: Exp, κ: Kontinuation, ρ: Environment[Addr], σ: Store[Addr, Abs]) extends Action[Exp, Abs, Addr]
case class ActionEval[Exp : Expression, Abs : AbstractValue, Addr : Address](e: Exp, ρ: Environment[Addr], σ: Store[Addr, Abs], a: Addr) extends Action[Exp, Abs, Addr]
case class ActionError[Exp : Expression, Abs : AbstractValue, Addr : Address](reason: String) extends Action[Exp, Abs, Addr]

class ANFSemantics[Abs, Addr](implicit ab: AbstractValue[Abs], abi: AbstractInjection[Abs],
                              ad: Address[Addr], adi: AddressInjection[Addr]) extends Semantics[ANFExp, Abs, Addr] {
  /* wtf scala */
  def abs = implicitly[AbstractValue[Abs]]
  def absi = implicitly[AbstractInjection[Abs]]
  def addr = implicitly[Address[Addr]]
  def addri = implicitly[AddressInjection[Addr]]
  def exp = implicitly[Expression[ANFExp]]
  sealed abstract class Kont extends Kontinuation {
    def subsumes(that: Kontinuation) = that.equals(this)
  }
  case class KontLet(v: String, body: ANFExp, env: Environment[Addr], next: Addr) extends Kont {
    override def toString() = s"KontLet(${v.toString})"
  }
  case class KontLetrec(v: String, a: Addr, body: ANFExp, env: Environment[Addr], next: Addr) extends Kont {
    override def toString() = s"KontLetrec(${v.toString})"
  }
  object KontHalt extends Kont {
    override def toString() = "KontHalt"
  }

  def atomicEval(e: ANFAtomicExp, ρ: Environment[Addr], σ: Store[Addr, Abs]): Either[String, Abs] = e match {
    case λ: ANFLambda => Right(absi.inject[ANFExp, Addr]((λ, ρ)))
    case ANFIdentifier(name) => ρ.lookup(name) match {
      case Some(a) => Right(σ.lookup(a))
      case None => Left(s"Unbound variable: $name")
    }
    case ANFValue(ValueString(s)) => Right(absi.inject(s))
    case ANFValue(ValueInteger(n)) => Right(absi.inject(n))
    case ANFValue(ValueBoolean(b)) => Right(absi.inject(b))
    case ANFValue(v) => Left(s"Unhandled value: ${v}")
  }

  def bindArgs(l: List[(String, Abs)], ρ: Environment[Addr], σ: Store[Addr, Abs]): (Environment[Addr], Store[Addr, Abs]) =
    l.foldLeft((ρ, σ))({ case ((ρ, sigma), (name, value)) => {
      val a = addri.variable(name)
      (ρ.extend(name, a), σ.extend(a, value))
    }})

  def stepEval(e: ANFExp, ρ: Environment[Addr], σ: Store[Addr, Abs], a: Addr) = e match {
    case ae: ANFAtomicExp => atomicEval(ae, ρ, σ) match {
      case Left(err) => Set(ActionError(err))
      case Right(v) => Set(ActionReachedValue(v, σ, a))
    }
    case ANFFuncall(f, args) =>
      /* TODO: monadic style */
      val init : Either[String, List[Abs]] = Right(List())
      args.foldLeft(init)((acc: Either[String, List[Abs]], arg: ANFAtomicExp) => acc match {
        case Right(l) => atomicEval(arg, ρ, σ) match {
          case Right(v) => Right(v :: l)
          case Left(err) => Left(err)
        }
        case Left(err) => Left(err)
      }) match {
        case Left(err) => Set(ActionError(err))
        case Right(argsv) =>
          atomicEval(f, ρ, σ) match {
            case Left(err) => Set(ActionError(err))
            case Right(fv) =>
              abs.foldValues(fv, (v) => abs.getClosure[ANFExp, Addr](v) match {
                case Some((ANFLambda(args, body), ρ)) => if (args.length == argsv.length) {
                  bindArgs(args.zip(argsv), ρ, σ) match {
                    case (ρ, σ) => Set(ActionEval(body, ρ, σ, a))
                  }
                } else { Set(ActionError(s"Arity error (${args.length} arguments expected, got ${argsv.length}")) }
                case Some((λ, _)) => Set(ActionError(s"Incorrect closure with lambda-expression ${λ}"))
                case None => abs.getPrimitive(v) match {
                  case Some((name, f)) => f(argsv) match {
                    case Right(res) => Set(ActionReachedValue(res, σ, a))
                    case Left(err) => Set(ActionError(err))
                  }
                  case None => Set(ActionError("Called value is not a function"))
                }
              })
          }
      }
    case ANFIf(cond, cons, alt) =>
      atomicEval(cond, ρ, σ) match {
        case Left(err) => Set(ActionError(err))
        case Right(v) => {
          val t = ActionEval(cons, ρ, σ, a)
          val f = ActionEval(alt, ρ, σ, a)
          if (abs.isTrue(v) && abs.isFalse(v)) { Set(t, f) } else if (abs.isTrue(v)) { Set(t) } else if (abs.isFalse(v)) { Set(f) } else { Set() }
        }
      }
    case ANFLet(variable, exp, body) =>
      Set(ActionPush(exp, KontLet(variable, body, ρ, a), ρ, σ))
    case ANFLetrec(variable, exp, body) => {
      val vara = addri.variable(variable)
      Set(ActionPush(exp, KontLetrec(variable, vara, body, ρ.extend(variable, vara), a), ρ.extend(variable, vara), σ.extend(vara, absi.bottom)))
    }
    case ANFSet(variable, value) => ρ.lookup(variable) match {
      case Some(vara) => atomicEval(value, ρ, σ) match {
        case Left(err) => Set(ActionError(err))
        case Right(v) => Set(ActionReachedValue(v, σ.extend(vara, v), a))
      }
      case None => Set(ActionError(s"Unbound variable: ${variable}"))
    }
    case ANFQuoted(sexp) => Set(ActionError("TODO: quoted values not yet handled"))
  }

  def stepKont(v: Abs, σ: Store[Addr, Abs], κ: Kontinuation) = κ match {
    case KontHalt => Set()
    case KontLet(variable, body, ρ, next) => {
      val vara = addri.variable(variable)
      Set(ActionEval(body, ρ.extend(variable, vara), σ.extend(vara, v), next))
    }
    case KontLetrec(variable, vara, body, ρ, next) =>
      Set(ActionEval(body, ρ, σ.extend(vara, v), next))
  }
}
