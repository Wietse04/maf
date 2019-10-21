package scalaam.modular

import core.Annotations.mutable
import scalaam.core._
import scalaam.util._
import scalaam.util.MonoidImplicits._

abstract class ModAnalysis[Expr <: Expression](program: Expr) {

  // parameterized by a 'intra-component' representation
  type IntraComponent
  val initialComponent: IntraComponent

  // an intra-analysis of a component can cause dependencies that impact the analysis of other components
  // an intra-analysis therefore can:
  // - register a dependency on an effect (e.g., when it reads an address)
  // - trigger an effect (e.g., when it writes to an address)
  protected trait Dependency
  // here, we track which components depend on which effects
  @mutable var deps = Map[Dependency,Set[IntraComponent]]().withDefaultValue(Set())
  private def addDep(component: IntraComponent, dep: Dependency) =
    deps += (dep -> (deps(dep) + component))

  // parameterized by an 'intra-component analysis'
  protected def intraAnalysis(component: IntraComponent): IntraAnalysis
  protected abstract class IntraAnalysis(val component: IntraComponent) {
    // keep track of dependencies triggered by this intra-analysis
    @mutable private[ModAnalysis] var deps = Set[Dependency]()
    protected def triggerDependency(dep: Dependency) = deps += dep
    protected def registerDependency(dep: Dependency) = addDep(component,dep)
    // keep track of components called by this intra-analysis
    @mutable private[ModAnalysis] var components = Set[IntraComponent]()
    protected def spawn(cmp: IntraComponent) = components += cmp
    // analyses the given component
    def analyze(): Unit
  }

  // inter-analysis using a simple worklist algorithm
  @mutable var work = Set[IntraComponent](initialComponent)
  @mutable var visited = Set[IntraComponent]()
  @mutable var allComponents = Set[IntraComponent](initialComponent)
  @mutable var componentDeps = Map[IntraComponent,Set[IntraComponent]]()
  def finished() = work.isEmpty
  def step() = {
    // take the next component
    val current = work.head
    work -= current
    // do the intra-analysis
    val intra = intraAnalysis(current)
    intra.analyze()
    // add the successors to the worklist
    val newComponents = intra.components.filterNot(visited)
    val componentsToUpdate = intra.deps.flatMap(deps)
    val succs = newComponents ++ componentsToUpdate
    work ++= succs
    // update the analysis
    visited += current
    allComponents ++= newComponents
    componentDeps += (current -> intra.components)
  }
  def analyze(): Unit = while(!finished()) { step() }
}

abstract class AdaptiveModAnalysis[Expr <: Expression](program: Expr) extends ModAnalysis(program) {

  // parameterized by an alpha function, which further 'abstracts' components
  // alpha can be used to drive an adaptive strategy for the analysis
  protected def alpha(cmp: IntraComponent): IntraComponent
  // based on this definition of alpha, we can induce 'compound versions' of this function
  protected def alphaSet[A](alphaA: A => A)(set: Set[A]): Set[A] = set.map(alphaA)
  protected def alphaMap[K, V : Monoid](alphaK: K => K, alphaV: V => V)(map: Map[K,V]): Map[K,V] =
    map.foldLeft(Map[K,V]().withDefaultValue(Monoid[V].zero)) { case (acc,(key,vlu)) =>
      val keyAbs = alphaK(key)
      acc + (keyAbs -> Monoid[V].append(acc(keyAbs),alphaV(vlu)))
    }
  // effects might require further abstraction too; subclasses can override this as needed ...
  protected def alphaDep(dep: Dependency): Dependency = dep

  // when alpha changes, we need to call this function to update the analysis' components
  def onAlphaChange() = {
    work            = alphaSet(alpha)(work)
    visited         = alphaSet(alpha)(visited)
    allComponents   = alphaSet(alpha)(allComponents)
    componentDeps   = alphaMap(alpha,alphaSet(alpha))(componentDeps)
    deps            = alphaMap(alphaDep,alphaSet(alpha))(deps)
  }
}