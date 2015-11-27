import scalaz.Scalaz._

class ConcurrentAAM[Exp : Expression, Abs : AbstractValue, Addr : Address, Time : Timestamp, TID : ThreadIdentifier]
    extends AbstractMachine[Exp, Abs, Addr, Time] {
  def abs = implicitly[AbstractValue[Abs]]
  def addr = implicitly[Address[Addr]]
  def exp = implicitly[Expression[Exp]]
  def time = implicitly[Timestamp[Time]]
  def thread = implicitly[ThreadIdentifier[TID]]

  def name = "ConcurrentAAM"
  val aam = new AAM[Exp, Abs, Addr, Time]
  import aam._

  type KontAddr = aam.KontAddr

  case class Context(control: Control, kstore: KontStore[KontAddr], a: KontAddr, t: Time) {
    def integrate1(tid: TID, a: KontAddr, action: Action[Exp, Abs, Addr])(threads: ThreadMap, results: ThreadResults):
        Option[(ThreadMap, ThreadResults, Store[Addr, Abs])] = action match {
      case ActionReachedValue(v, σ) => Some((threads.update(tid, Context(ControlKont(v), kstore, a, t)), results, σ))
      case ActionPush(e, frame, ρ, σ) => {
        val next = NormalKontAddress(e, addr.variable("__kont__", t))
        Some((threads.update(tid, Context(ControlEval(e, ρ), kstore.extend(next, Kont(frame, a)), next, t)), results, σ))
      }
      case ActionEval(e, ρ, σ) => Some((threads.update(tid, Context(ControlEval(e, ρ), kstore, a, t)), results, σ))
      case ActionStepIn(fexp, _, e, ρ, σ, _) => Some((threads.update(tid, Context(ControlEval(e, ρ), kstore, a, time.tick(t, fexp))), results, σ))
      case ActionError(err) => Some((threads.update(tid, Context(ControlError(err), kstore, a, t)), results, Store.empty[Addr, Abs]))
      case ActionSpawn(tid2: TID, e, ρ, act) =>
        integrate1(tid, a, act)(threads.add(tid2, Context(ControlEval(e, ρ), new KontStore[KontAddr](), HaltKontAddress, t)), results)
      case ActionJoin(tid2, σ) => ??? /* TODO: if (results.contains(tid2)) {
        Some((threads.update(tid, Context(ControlKont(results.get(tid2), kstore, a))), results, σ))
      } else {
        None
      } */
    }

    def integrate(tid: TID, a: KontAddr, actions: Set[Action[Exp, Abs, Addr]], threads: ThreadMap, results: ThreadResults):
        (Set[(ThreadMap, ThreadResults, Store[Addr, Abs])]) =
      actions.map(action => integrate1(tid, a, action)(threads, results)).flatMap({
        case Some(res) => Set[(ThreadMap, ThreadResults, Store[Addr, Abs])](res)
        case None => Set[(ThreadMap, ThreadResults, Store[Addr, Abs])]()
      })

    def step(sem: Semantics[Exp, Abs, Addr, Time], tid: TID, store: Store[Addr, Abs], threads: ThreadMap, results: ThreadResults):
        (Set[(ThreadMap, ThreadResults, Store[Addr, Abs])]) = control match {
      case ControlEval(e, ρ) => integrate(tid, a, sem.stepEval(e, ρ, store, t), threads, results)
      case ControlKont(v) if halted && tid != thread.initial =>
        /* TODO: we could avoid distinguishing the initial thread, and just get the
         * final results at its location in results */
        Set((threads.remove(tid), results.add(tid, v), store))
      case ControlKont(v) if abs.isError(v) => Set()
      case ControlKont(v) => kstore.lookup(a).flatMap({
        case Kont(frame, next) => integrate(tid, next, sem.stepKont(v, frame, store, t), threads, results)
      })
      case ControlError(_) => Set()
    }

    def halted: Boolean = control match {
      case ControlEval(_, _) => false
      case ControlKont(v) => a == HaltKontAddress || abs.isError(v)
      case ControlError(_) => true
    }
  }

  case class ThreadMap(content: Map[TID, Set[Context]]) {
    def get(tid: TID): Set[Context] = content.getOrElse(tid, Set())
    def tids: Set[TID] = content.keys.toSet
    def update(tid: TID, context: Context): ThreadMap =
      ThreadMap(content + (tid -> Set(context))) /* TODO: abstract thread counting, join */
    def add(tid: TID, context: Context): ThreadMap =
      ThreadMap(content + (tid -> (get(tid) + context)))
    def remove(tid: TID): ThreadMap =
      ThreadMap(content - tid)
    def join(that: ThreadMap): ThreadMap = ThreadMap(this.content |+| that.content) /* TODO: does this correctly joins sets? */
    def forall(f: ((TID, Set[Context])) => Boolean): Boolean = content.forall(f)
  }

  case class ThreadResults(content: Map[TID, Abs]) {
    /* TODO: what if two threads share tid, one is done but not the other? -> use thread counting to know more*/
    def isDone(tid: TID): Boolean = content.contains(tid)
    def get(tid: TID): Abs = content.getOrElse(tid, abs.bottom)
    def add(tid: TID, v: Abs): ThreadResults = ThreadResults(content + (tid -> abs.join(get(tid), v)))
  }

  case class State(threads: ThreadMap, results: ThreadResults, store: Store[Addr, Abs]) {
    def step(sem: Semantics[Exp, Abs, Addr, Time], tid: TID): Set[State] =
      threads.get(tid).flatMap(ctx => ctx.step(sem, tid, store, threads, results).map({
        case (threads, results, store) => State(threads, results, store)
      }))
    def stepAll(sem: Semantics[Exp, Abs, Addr, Time]): Set[(TID, State)] =
      threads.tids.foldLeft(Set[(TID, State)]())((acc, tid) => step(sem, tid).foldLeft(acc)((acc, st) => acc + (tid -> st)))

    def halted: Boolean = threads.forall({
      case (_, ctxs) => ctxs.forall(_.halted)
    })

    override def toString = threads.tids.map(tid =>
      s"$tid: " + threads.get(tid).map(ctx => ctx.control).mkString(", ")
    ).mkString("\n")
  }

  object State {
    def inject(exp: Exp) = {
      val st = new aam.State(exp)
      State(ThreadMap(Map[TID, Set[Context]](thread.initial -> Set(Context(st.control, st.kstore, st.a, st.t)))),
        ThreadResults(Map[TID, Abs]()), st.σ)
    }
  }

  case class ConcurrentAAMOutput(halted: Set[State], count: Int, t: Double, graph: Option[Graph[State]])
      extends Output[Abs] {
    def finalValues = halted.flatMap(st => st.threads.get(thread.initial).flatMap(ctx => ctx.control match {
      case ControlKont(v) => Set[Abs](v)
      case _ => Set[Abs]()
    }))
    def containsFinalValue(v: Abs) = finalValues.exists(v2 => abs.subsumes(v2, v))
    def numberOfStates = count
    def time = t
    def toDotFile(path: String) = graph match {
      case Some(g) => g.toDotFile(path, _.toString.take(40),
        (s) => if (halted.contains(s)) { "#FFFFDD" } else { "#FFFFFF" })
      case None =>
        println("Not generating graph because no graph was computed")
    }
  }

  @scala.annotation.tailrec
  private def loop(todo: Set[State], visited: Set[State],
    halted: Set[State], startingTime: Long, graph: Option[Graph[State]],
    sem: Semantics[Exp, Abs, Addr, Time]): ConcurrentAAMOutput = {
    if (visited.size % 100 == 0) println(visited.size)
    if (visited.size > 30000) {
      ConcurrentAAMOutput(halted, visited.size,
        (System.nanoTime - startingTime) / Math.pow(10, 9), graph)
    } else {
    todo.headOption match {
      case Some(s) =>
        if (visited.contains(s)) {
          loop(todo.tail, visited, halted, startingTime, graph, sem)
        } else if (s.halted) {
          loop(todo.tail, visited + s, halted + s, startingTime, graph, sem)
        } else {
          val succs = s.stepAll(sem).map(_._2)
          val newGraph = graph.map(_.addEdges(succs.map(s2 => (s, s2))))
          loop(todo.tail ++ succs, visited + s, halted, startingTime, newGraph, sem)
        }
      case None => ConcurrentAAMOutput(halted, visited.size,
        (System.nanoTime - startingTime) / Math.pow(10, 9), graph)
    }
    }
  }

  def eval(exp: Exp, sem: Semantics[Exp, Abs, Addr, Time], graph: Boolean): Output[Abs] =
    loop(Set(State.inject(exp)), Set(), Set(), System.nanoTime,
      if (graph) { Some (new Graph[State]()) } else { None },
      sem)
}