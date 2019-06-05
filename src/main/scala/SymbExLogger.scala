// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon

import java.io.File
import java.nio.file.{Files, Path, Paths}

import scala.annotation.elidable
import scala.annotation.elidable._
import viper.silver.ast
import viper.silver.verifier.AbstractError
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.decider.PathConditionStack
import viper.silicon.interfaces.state.NonQuantifiedChunk
import viper.silicon.reporting.DefaultStateFormatter
import viper.silicon.state._
import viper.silicon.state.terms._
import viper.silver.cfg.silver.SilverCfg.SilverEdge

/* TODO: InsertionOrderedSet is used by the logger, but the insertion order is
 *       probably irrelevant for logging. I.e. it might be ok if these sets were
 *       traversed in non-deterministic order.
 */

/*
 *  For instructions on how to use/visualise recording, have a look at
 *  /utils/symbolicRecording/README_symbolicRecord.txt
 *
 * Overall concept:
 * 1) SymbExLogger Object:
 *    Is used as interface to access the logs. Code from this file that is used in Silicon
 *    should only be used via SymbExLogger. Contains a list of SymbLog, one SymbLog
 *    per method/function/predicate (member). The method 'currentLog()' gives access to the log
 *    of the currently executed member.
 * 2) SymbLog:
 *    Contains the log for a member. Most important methods: insert/collapse. To 'start'
 *    a record use insert, to finish the recording use collapse. There should be as many calls
 *    of collapse as of insert (theoretically; practically this is not possible due to branching.
 *    To avoid such cases, each insert gets an identifier, which is then used by collapse, to avoid
 *    multiple collapses per insert).
 * 3) Records:
 *    The basic abstract record type is SymbolicRecord. There is one record type for each of the
 *    four basic symbolic primitives evaluate, execute, consume and produce. For constructs of special
 *    interest (e.g., if-then-else-branching), there are separate records.
 *    The basic record looks conceptually as follows:
 *
 *    SymbolicRecord {
 *      subs = List[SymbolicRecord]
 *    }
 *
 *    Example to illustrate the way a silver program is logged:
 *    Assume the following silver code:
 *
 *    method m() {
 *      var a: Int
 *      a := 1
 *      a := a+2
 *    }
 *
 *    This results in a log that can be visualized as a
 *    simple tree (see: SimpleTreeRenderer) as follows:
 *
 *    method m
 *      execute a := 1
 *        evaluate 1
 *      execute a := a + 2
 *        evaluate a
 *        evaluate 2
 *
 *    The order of insert/collapse is as follows:
 *    insert(method), insert(execute), insert (evaluate),
 *    collapse(evaluate), collapse(execute),
 *    insert(execute), insert(evaluate) collapse(evaluate),
 *    insert(evaluate), collapse(evaluate)
 *    collapse(execute), collapse(method)
 *
 *    Collapse basically 'removes one indentation', i.e., brings you one level higher in the tree.
 *    For an overview of 'custom' records (e.g., for Branching, local branching, method calls etc.),
 *    have a look at the bottom of this file for a guide in how to create such records or take a look
 *    at already existing examples such as IfThenElseRecord, CondExpRecord or MethodCallRecord.
 */

object SymbExLogger {
  /** List of logged Method/Predicates/Functions. **/
  var memberList = List[SymbLog]()

  var enabled = false

  /** Config of Silicon. Used by StateFormatters. **/
  private var config: Config = _

  /** Add a new log for a method, function or predicate (member).
    *
    * @param member Either a MethodRecord, PredicateRecord or a FunctionRecord.
    * @param s      Current state. Since the body of the method (predicate/function) is not yet
    *               executed/logged, this is usually the empty state (use Σ(Ø, Ø, Ø) for empty
    *               state).
    * @param c      Current context.
    */
  @elidable(INFO)
  def insertMember(member: ast.Member, s: State, pcs: PathConditionStack) {
    memberList = memberList ++ List(new SymbLog(member, s, pcs))
  }

  /** Use this method to access the current log, e.g., to access the log of the method
    * that gets currently symbolically executed.
    *
    * @return Returns the current method, predicate or function that is being logged.
    */
  def currentLog(): SymbLog = {
    if (enabled)
      memberList.last
    else NoopSymbLog
  }

  def endMember(): Unit = {
    if (currentLog() != null
      && currentLog().main != null) {
      currentLog().main.endTimeMs = System.currentTimeMillis()
    }
  }

  /**
    * Passes config from Silicon to SymbExLogger.
    * Config is assigned only once, further calls are ignored.
    *
    * @param c Config of Silicon.
    */
  def setConfig(c: Config) {
    if (config == null) {
      config = c
      // In both cases we need to record the trace
      setEnabled(config.ideModeAdvanced())
    }
  }

  @elidable(INFO)
  private def setEnabled(b: Boolean) {
    enabled = b
  }

  /** Gives back config from Silicon **/
  def getConfig(): Config = {
    config
  }

  /**
    * Simple string representation of the logs.
    */
  def toSimpleTreeString: String = {
    if (enabled) {
      val simpleTreeRenderer = new SimpleTreeRenderer()
      simpleTreeRenderer.render(memberList)
    } else ""
  }

  /**
    * Simple string representation of the logs, but contains only the types of the records
    * and not their values. Original purpose was usage for unit testing.
    */
  def toTypeTreeString(): String = {
    if (enabled) {
      val typeTreeRenderer = new TypeTreeRenderer()
      typeTreeRenderer.render(memberList)
    } else ""
  }

  /**
    * Writes a .DOT-file with a representation of all logged methods, predicates, functions.
    * DOT-file can be interpreted with GraphViz (http://www.graphviz.org/)
    */
  @elidable(INFO)
  def writeDotFile() {
    if (config.writeTraceFile()) {
      val dotRenderer = new DotTreeRenderer()
      val str = dotRenderer.render(memberList)
      val pw = new java.io.PrintWriter(new File(getOutputFolder() + "dot_input.dot"))
      try pw.write(str) finally pw.close()
    }
  }

  /**
    * Writes a .JS-file that can be used for representation of the logged methods, predicates
    * and functions in a HTML-file.
    */
  @elidable(INFO)
  def writeJSFile() {
    if (config.writeTraceFile()) {
      val pw = new java.io.PrintWriter(new File(getOutputFolder() + "executionTreeData.js"))
      try pw.write(toJSString()) finally pw.close()
    }
  }

  /** A JSON representation of the log, used when sending back messages or when writing data to a
    * file.
    */
  @elidable(INFO)
  def toJSString(): String = new JSTreeRenderer().render(memberList)

  protected def getOutputFolder(): String = {
    ""
  }

  /** Path to the file that is being executed. Is used for UnitTesting. **/
  var filePath: Path = null

  /** Unit Testing **/
  var unitTestEngine: SymbExLogUnitTest = null

  /** Initialize Unit Testing. Should be done AFTER the file to be tested is known. **/
  def initUnitTestEngine() {
    if (filePath != null)
      unitTestEngine = new SymbExLogUnitTest(filePath)
  }

  /**
    * Resets the SymbExLogger-object, to make it ready for a new file.
    * Only needed when several files are verified together (e.g., sbt test).
    */
  def reset() {
    memberList = List[SymbLog]()
    unitTestEngine = null
    filePath = null
    config = null
  }

  def resetMemberList() {
    memberList = List[SymbLog]()
  }

  def checkMemberList(): String = {
    new ExecTimeChecker().render(memberList)
  }

  /**
    * Converts memberList to a tree of GenericNodes
    */
  def convertMemberList(): GenericNode = {
    new GenericNodeRenderer().render(memberList)
  }

  def writeChromeTraceFile(genericNode: GenericNode): Unit = {
    if (config.writeTraceFile()) {
      val chromeTraceRenderer = new ChromeTraceRenderer()
      val str = chromeTraceRenderer.render(List(genericNode))
      val pw = new java.io.PrintWriter(new File(getOutputFolder() + "chromeTrace.json"))
      try pw.write(str) finally pw.close()
    }
  }

  def writeGenericNodeJsonFile(genericNode: GenericNode): Unit = {
    if (config.writeTraceFile()) {
      val jsonRenderer = new JsonRenderer()
      val str = jsonRenderer.render(List(genericNode))
      val pw = new java.io.PrintWriter(new File(getOutputFolder() + "genericNodes.json"))
      try pw.write(str) finally pw.close()
    }
  }
}

//========================= SymbLog ========================

/**
  * Concept: One object of SymbLog per Method/Predicate/Function. SymbLog
  * is used in the SymbExLogger-object.
  */
class SymbLog(v: ast.Member, s: State, pcs: PathConditionStack) {
  var main = v match {
    case m: ast.Method => new MethodRecord(m, s, pcs)
    case p: ast.Predicate => new PredicateRecord(p, s, pcs)
    case f: ast.Function => new FunctionRecord(f, s, pcs)
    case default => null
  }
  if (main != null) {
    main.startTimeMs = System.currentTimeMillis()
  }

  // Maps macros to their body
  private var _macros = Map[App, Term]()
  private var stack = List[SymbolicRecord](main)
  private var sepCounter = 0
  // private var sepSet = InsertionOrderedSet[Int]()
  private var sepSet = Map[Int, SymbolicRecord]()
  private var ignoredSepSet = InsertionOrderedSet[Int]()

  private def current(): SymbolicRecord = {
    stack.head
  }

  /**
    * Inserts a record. For usage of custom records, take a look at the guidelines in SymbExLogger.scala.
    * For every insert, there MUST be a call of collapse at the appropriate place in the code. The order
    * of insert/collapse-calls defines the record-hierarchy.
    *
    * @param s Record for symbolic execution primitive.
    * @return Identifier of the inserted record, must be given as argument to the
    *         respective call of collapse.
    */
  def insert(s: SymbolicRecord): Int = {

    if (!isUsed(s.value) || isRecordedDifferently(s))
      return -1

    if (s.startTimeMs == 0) {
      s.startTimeMs = System.currentTimeMillis()
    }
    current().subs = current().subs ++ List(s)
    stack = s :: stack
    sepCounter = sepCounter + 1
    // sepSet = sepSet + sepCounter
    sepSet = sepSet + ((sepCounter, s))
    sepCounter
  }

  /** Record the last prover query that failed.
    *
    * This is used to record failed SMT queries, that ultimately led Silicon
    * to a verification failure. Whenever a new SMT query is successful, then
    * the currently recorded one is supposed to be discarded (via the
    * discardSMTQuery method), because it did not cause a failure.
    *
    * @param query The query to be recorded.
    */
  def setSMTQuery(query: Term): Unit = {
    if (main != null) {
      main.lastFailedProverQuery = Some(query)
    }
  }

  /** Discard the currently recorded SMT query.
    *
    * This is supposed to be called when we know the recorded SMT query cannot
    * have been the reason for a verification failure (e.g. a new query has
    * been performed afterwards).
    */
  def discardSMTQuery(): Unit = {
    if (main != null) {
      main.lastFailedProverQuery = None
    }
  }

  def topOfStackInIgnoredSepSetCheck: Boolean = true

  /**
    * 'Finishes' the recording at the current node and goes one level higher in the record tree.
    * There should be only one call of collapse per insert.
    *
    * @param v The node that will be 'collapsed'. Is only used for filtering-purposes, can be null.
    * @param n The identifier of the node (can NOT be null). The identifier is created by insert (return
    *          value).
    */
  @elidable(INFO)
  def collapse(v: ast.Node, n: Int) {
    if (n == -1) {
      return
    }
    if (sepSet.contains(n)) {
      val record = sepSet(n)
      sepSet = sepSet - n
      if (isUsed(v)) {
        if (current().endTimeMs == 0) {
          current().endTimeMs = System.currentTimeMillis()
        }
        assert(record == stack.head)
        stack = stack.tail
      }

      if (topOfStackInIgnoredSepSetCheck) {
        // check if top of stack is in ignoredSepSet:
        for (i <- sepSet.keys) {
          if (stack.head equals sepSet(i)) {
            if (ignoredSepSet contains i) {
              collapse(null, i)
              return
            }
          }
        }
      }
    } else {
      ignoredSepSet = ignoredSepSet + n
    }
  }

  type LoggerState = (Map[Int, SymbolicRecord], // sepSet
    List[SymbolicRecord],                       // stack
    InsertionOrderedSet[Int])                   // ignoredSepSet

  /**
    * Quite a hack. Is used for impure Branching, where 'redundant' collapses in the continuation
    * can mess up the logging-hierarchy. Idea: Just remove all identifiers from the collapse-Set, so
    * collapse will NOT collapse records that were inserted outside of branching but collapsed inside
    * a branch due to continuation. Currently, this is only used for impure Branching (CondExp/Implies
    * in Producer/Consumer).
    */
  @elidable(INFO)
  def newInitializeBranching(): LoggerState =  {
    val state = (sepSet, stack, ignoredSepSet)
    // sepSet = InsertionOrderedSet[Int]()
    sepSet = Map[Int, SymbolicRecord]()
    ignoredSepSet = InsertionOrderedSet[Int]()
    state
  }

  @elidable(INFO)
  def newRestoreState(prevState: LoggerState, otherBranchStates: List[LoggerState], branchesCount: Int): Unit = {
    // assert(thnState._1.equals(sepSet))
    //  assert(thnState._2.equals(stack))
    // assert(branchesCount >= otherBranchStates.length)
    val branchStates = otherBranchStates :+ (sepSet, stack, ignoredSepSet)

    sepSet = prevState._1
    stack = prevState._2

    // ignoredSepSet contains all elements that appear branchesCount-many times in branchStates and the set before branching (i.e. prevState._3)
    val branchIgnoredSepSets = branchStates.map(state => state._3)
    var ignoredSepCount: Map[Int, Int] = Map[Int, Int]() // maps sep to its count
    branchIgnoredSepSets.foreach(ignoredSeps => ignoredSeps.foreach(sep => {
      val sepCount = ignoredSepCount.get(sep)
      ignoredSepCount = ignoredSepCount + ((sep, sepCount.getOrElse(0) + 1))
    }))
    // count of each sep is calculated, now filter based on branchesCount
    ignoredSepSet = InsertionOrderedSet(ignoredSepCount.filter(entry => entry._2 >= branchesCount).keys)
    ignoredSepSet = ignoredSepSet ++ prevState._3
    // TODO is a check whether top of stack is in ignoredSepSet here necessary? (similarly to collapse)
  }

  /**
    * Quite a hack, similar purpose as initializeBranching. Is used to make sure that an else-branch
    * is logged correctly, which is sometimes not the case in branching when collapses from the continuation
    * in the If-branch remove the branching-record itself from the stack. Currently only used for impure
    * Branching (CondExp/Implies in Producer/Consumer).
    *
    * @param s Record that should record the else-branch.
    */
  @elidable(INFO)
  def newPrepareOtherBranch(state: LoggerState): LoggerState = {
    val thenState = (sepSet, stack, ignoredSepSet)
    // sepSet = InsertionOrderedSet[Int]()
    sepSet = Map[Int, SymbolicRecord]()
    stack = state._2
    ignoredSepSet = InsertionOrderedSet[Int]()
    thenState
  }

  def macros() = _macros

  def addMacro(m: App, body: Term): Unit = {
    _macros = _macros + (m -> body)
  }

  private def isRecordedDifferently(s: SymbolicRecord): Boolean = {
    s.value match {
      case v: ast.MethodCall =>
        s match {
          case _: MethodCallRecord => false
          case _ => true
        }
      case v: ast.CondExp =>
        s match {
          case _: EvaluateRecord | _: ConsumeRecord | _: ProduceRecord => true
          case _ => false
        }
      case v: ast.Implies =>
        s match {
          case _: ConsumeRecord | _: ProduceRecord => true
          case _ => false
        }

      case _ => false
    }
  }

  private def isUsed(node: ast.Node): Boolean = {
    node match {
      case stmt: ast.Stmt => {
        stmt match {
          case _: ast.Seqn =>
            false
          case _ =>
            true
        }
      }
      case _ => true
    }
  }

  override def toString: String = new SimpleTreeRenderer().renderMember(this)
}

object NoopSymbLog extends SymbLog(null, null, null) {
  override def insert(s: SymbolicRecord): Int = 0
}

//===== Renderer Classes =====

sealed trait Renderer[S, T] {
  def renderMember(s: S): T

  def render(memberList: List[S]): T
}

class DotTreeRenderer extends Renderer[SymbLog, String] {

  def render(memberList: List[SymbLog]): String = {
    var str: String = "digraph {\n"
    str = str + "node [shape=rectangle];\n\n"

    for (m <- memberList) {
      str = str + renderMember(m) + "\n\n"
    }

    str = str + "}"
    str
  }

  def renderMember(s: SymbLog): String = {
    val main = s.main
    var output = ""

    output += "    " + main.dotNode() + " [label=" + main.dotLabel() + "];\n"
    output += subsToDot(main)
    output
  }

  private var previousNode = ""
  private var unique_node_nr = this.hashCode()

  private def unique_node_number(): Int = {
    unique_node_nr = unique_node_nr + 1
    unique_node_nr
  }

  private def subsToDot(s: SymbolicRecord): String = {
    previousNode = s.dotNode()

    var output = ""

    s match {
      case imp: GlobalBranchRecord => {
        val imp_parent = previousNode
        for (rec <- imp.thnSubs) {
          output += "    " + rec.dotNode() + " [label=" + rec.dotLabel() + "];\n"
          output += "    " + previousNode + " -> " + rec.dotNode() + ";\n"
          output += subsToDot(rec)
        }
        previousNode = imp_parent
        for (rec <- imp.elsSubs) {
          output += "    " + rec.dotNode() + " [label=" + rec.dotLabel() + "];\n"
          output += "    " + previousNode + " -> " + rec.dotNode() + ";\n"
          output += subsToDot(rec)
        }
      }

      case mc: MethodCallRecord => {
        val mc_parent = previousNode
        output += "    " + mc.dotNode() + " [label=" + mc.dotLabel() + "];\n"
        previousNode = mc.dotNode()

        for (p <- mc.parameters) {
          output += "    " + p.dotNode() + " [label=\"parameter: " + p.toSimpleString() + "\"];\n"
          output += "    " + previousNode + " -> " + p.dotNode() + ";\n"
          output += subsToDot(p)
        }
        previousNode = mc.dotNode()

        output += "    " + mc.precondition.dotNode() + " [label=\"precondition: " + mc.precondition.toSimpleString() + "\"];\n"
        output += "    " + previousNode + " -> " + mc.precondition.dotNode() + ";\n"
        output += subsToDot(mc.precondition)
        previousNode = mc.dotNode()

        output += "    " + mc.postcondition.dotNode() + " [label=\"postcondition: " + mc.postcondition.toSimpleString() + "\"];\n"
        output += "    " + previousNode + " -> " + mc.postcondition.dotNode() + ";\n"
        output += subsToDot(mc.postcondition)
        previousNode = mc.dotNode()


      }
      case _ => {
        if (s.subs.isEmpty)
          return ""
        for (rec <- s.subs) {
          output += "    " + rec.dotNode() + " [label=" + rec.dotLabel() + "];\n"
          output += "    " + previousNode + " -> " + rec.dotNode() + ";\n"
          output += subsToDot(rec)
        }
      }
    }
    output
  }
}

object JsonHelper {
  def pair(name: String, value: String): String = {
    "\"" + name + "\": " + "\"" + escape(value) + "\""
  }

  def pair(name: String, value: Boolean): String = {
    "\"" + name + "\":" + value
  }

  def pair(name: String, value: Long): String = {
    "\"" + name + "\":" + value
  }

  def pair(name: String, value: List[Int]): String = {
    "\"" + name + "\":[" + value.mkString(",") + "]"
  }

  def escape(s: String): String = {
    var res = s
    var i = 0
    while (i < res.length()) {
      if (res(i).equals('\n')) {
        res = res.substring(0, i - 1) + "\\n" + res.substring(i + 1, res.length())
        i += 1
      } else if (res(i).equals('\\')) {
        res = res.substring(0, i - 1) + "\\\\" + res.substring(i + 1, res.length())
        i += 1
      }
      i += 1
    }
    res
  }
}

class JSTreeRenderer extends Renderer[SymbLog, String] {

  val stateFormatter: DefaultStateFormatter
  = new DefaultStateFormatter()

  def render(memberList: List[SymbLog]): String = {
    "var executionTreeData = [\n" + memberList.map(s => renderMember(s)).fold("") { (a, b) => if (a.isEmpty) b else a + ", \n" + b } + "]\n"
  }

  def renderMember(member: SymbLog): String = {
    recordToJS(member.main) + "\n"
  }

  private def recordToJS(s: SymbolicRecord): String = {
    var output = ""
    val kind = "kind"
    val children = "\"children\""
    val open = JsonHelper.pair("open", true)

    s match {
      case gb: GlobalBranchRecord => {
        output += "{" + gb.toJson() + "," + open + printState(gb)
        output += "\n," + children + ": [\n"
        output += "{" + JsonHelper.pair(kind, "Branch 1") + "," + open
        output += ",\n" + children + ": [\n"
        output += combine(gb.thnSubs)
        output += "]},\n"
        output += "{" + JsonHelper.pair(kind, "Branch 2") + "," + open
        output += ",\n" + children + ": [\n"
        output += combine(gb.elsSubs)
        output += "]}\n"
        output += "]}"
      }
      case mc: MethodCallRecord => {
        output += "{" + mc.toJson() + "," + open + printState(mc)
        output += "\n," + children + ": [\n"

        output += "{" + JsonHelper.pair(kind, "parameters") + "," + open
        output += "\n," + children + ": [\n"
        output += combine(mc.parameters)
        output += "]},"

        output += "{" + JsonHelper.pair(kind, "precondition") + "," + open + printState(mc.precondition)
        output += "\n," + children + ": [\n"
        output += recordToJS(mc.precondition)
        output += "]},"

        output += "{" + JsonHelper.pair(kind, "postcondition") + "," + open + printState(mc.postcondition)
        output += "\n," + children + ": [\n"
        output += recordToJS(mc.postcondition)
        output += "]}"

        output += "]}"
      }
      case cr: CommentRecord => {
        output += "{" + JsonHelper.pair(kind, "comment") + "," + cr.toJson() + "," + open + "}"
      }
      case _ => {
        var innerValue = s.toJson()
        if(innerValue != ""){
          innerValue += ","
        }
        output += "{" + innerValue + open + printState(s)
        if (!s.subs.isEmpty) {
          output += ",\n" + children + ": [\n"
          output += combine(s.subs)
          output += "]"
        }
        output += "}"
      }
    }
    output
  }

  def combine(list: List[SymbolicRecord]): String = {
    list.map(s => recordToJS(s)).fold("") { (a, b) => if (a.isEmpty) b else a + ",\n" + b } + "\n"
  }

  def printState(s: SymbolicRecord): String = {
    var res = ""
    if (s.state != null) {
      var σ = s.state.asInstanceOf[State]
      res = ",\"prestate\":" + JsonHelper.escape(stateFormatter.toJson(σ, s.pcs))
    }
    res
  }
}

class ExecTimeChecker extends Renderer[SymbLog, String] {
  def render(memberList: List[SymbLog]): String = {
    var res = List[String]()
    for (m <- memberList) {
      res = res :+ renderMember(m)
    }
    res.filter(check => check != "")
      .mkString("\n")
  }

  def renderMember(member: SymbLog): String = {
    checkRecord(member.main)
  }

  def checkRecord(s: SymbolicRecord): String = {
    var checks = getSubs(s)
      .map(checkRecord)
      .filter(check => check != "")
    // ignore unreachable records:
    var ignore = false
    s match {
      case cr: CommentRecord =>
        if (cr.comment.equals("Unreachable")) {
          ignore = true
        }
      case _ =>
    }
    if (!ignore &&
      (s.startTimeMs == 0
      || s.endTimeMs == 0)) {
      checks = checks :+ "incomplete exec timing: " + s.toString()
    }
    checks.mkString("\n")
  }

  def getSubs(s: SymbolicRecord): List[SymbolicRecord] = {
    s match {
      case ce: ConditionalEdgeRecord =>
        ce.subs ++ List(ce.cond) ++ ce.thnSubs
      case gb: GlobalBranchRecord =>
        gb.subs ++ List(gb.cond) ++ gb.thnSubs ++ gb.elsSubs
      case lb: LocalBranchRecord =>
        lb.subs ++ List(lb.cond) ++ lb.thnSubs ++ lb.elsSubs
      case mc: MethodCallRecord =>
        mc.subs ++ List(mc.precondition, mc.postcondition) ++ mc.parameters
      case _ => s.subs
    }
  }
}

class SimpleTreeRenderer extends Renderer[SymbLog, String] {
  def render(memberList: List[SymbLog]): String = {
    var res = ""
    for (m <- memberList) {
      res = res + renderMember(m) + "\n"
    }
    res
  }

  def renderMember(member: SymbLog): String = {
    toSimpleTree(member.main, 1)
  }

  def filter(s: SymbolicRecord): Boolean = {
    s match {
      case br: CfgBranchRecord =>
        if (br.branchSubs.length == 1) {
          br.branchSubs.head.forall(filter)
        } else {
          false
        }
      case ue: UnconditionalEdgeRecord => ue.subs.forall(filter)
      case ce: ConditionalEdgeRecord =>
        filter(ce.cond) && ce.thnSubs.forall(filter)
      case da: DeciderAssumeRecord => true
      case _ => false
    }
  }

  def toSimpleTree(s: SymbolicRecord, n: Int): String = {
    var indent = ""
    for (i <- 1 to n) {
      indent = "  " + indent
    }
    var str = ""
    s match {
      case br: CfgBranchRecord => {
        if (br.branchSubs.length == 1) {
          // ignore this record
          var branchSubCount = 0
          for (subIndex <- br.branchSubs.head.indices) {
            val branchSubs = br.branchSubs.head
            if (!filter(branchSubs(subIndex))) {
              var subIndent = ""
              if (branchSubCount != 0) {
                subIndent = indent.substring(2)
              }
              str = str + subIndent + toSimpleTree(br.branchSubs.head(subIndex), n)
              branchSubCount = branchSubCount + 1
            }
          }
        } else {
          for (branchIndex <- br.branchSubs.indices) {
            val branchSubs = br.branchSubs(branchIndex)
            var branchIndent = ""
            if (branchIndex != 0) {
                branchIndent = indent.substring(2)
            }
            str = str + branchIndent + "Branch " + (branchIndex + 1).toString + ":\n"
            for (sub <- branchSubs) {
              if (!filter(sub)) {
                str = str + indent + toSimpleTree(sub, n + 1)
              }
            }
          }
        }
      }
      case ce: ConditionalEdgeRecord => {
        // ignore this record
        if (!filter(ce.cond)) {
          str = str + toSimpleTree(ce.cond, n)
        }
        for (sub <- ce.thnSubs) {
          if (!filter(sub)) {
            str = str + indent.substring(2) + toSimpleTree(sub, n)
          }
        }
      }
      case ue: UnconditionalEdgeRecord => {
        // ignore this record
        var subCount = 0
        for (subIndex <- ue.subs.indices) {
          var subIndent = ""
          if (subCount != 0) {
            subIndent = indent.substring(2)
          }
          if (!filter(ue.subs(subIndex))) {
            str = str + subIndent + toSimpleTree(ue.subs(subIndex), n)
            subCount = subCount + 1
          }
        }
      }
      case gb: GlobalBranchRecord => {
        str = str + "GlobalBranch:\n"
        if (!filter(gb.cond)) {
          str = str + indent + toSimpleTree(gb.cond, n + 1)
        }
        str = str + indent.substring(2) + "Branch 1:\n"
        for (sub <- gb.thnSubs) {
          if (!filter(sub)) {
            str = str + indent + toSimpleTree(sub, n + 1)
          }
        }

        str = str + indent.substring(2) + "Branch 2:\n"
        for (sub <- gb.elsSubs) {
          if (!filter(sub)) {
            str = str + indent + toSimpleTree(sub, n + 1)
          }
        }
      }
      case lb: LocalBranchRecord => {
        str = str + "LocalBranch:\n"
        if (!filter(lb.cond)) {
          str = str + indent + toSimpleTree(lb.cond, n + 1)
        }
        str = str + indent.substring(2) + "Branch 1:\n"
        for (sub <- lb.thnSubs) {
          if (!filter(sub)) {
            str = str + indent + toSimpleTree(sub, n + 1)
          }
        }

        str = str + indent.substring(2) + "Branch 2:\n"
        for (sub <- lb.elsSubs) {
          if (!filter(sub)) {
            str = str + indent + toSimpleTree(sub, n + 1)
          }
        }
      }
      case mc: MethodCallRecord => {
        str = str + mc.toString() + "\n"
        if (!filter(mc.precondition)) {
          str = str + indent + "precondition: " + toSimpleTree(mc.precondition, n + 1)
        }
        if (!filter(mc.postcondition)) {
          str = str + indent + "postcondition: " + toSimpleTree(mc.postcondition, n + 1)
        }
        for (p <- mc.parameters) {
          if (!filter(p)) {
            str = str + indent + "parameter: " + toSimpleTree(p, n + 1)
          }
        }
      }
      case _ => {
        str = str + s.toString() + "\n"
        for (sub <- s.subs) {
          if (!filter(sub)) {
            str = str + indent + toSimpleTree(sub, n + 1)
          }
        }
      }
    }
    str
  }
}

class TypeTreeRenderer extends Renderer[SymbLog, String] {
  def render(memberList: List[SymbLog]): String = {
    var res = ""
    for (m <- memberList) {
      res = res + renderMember(m) + "\n"
    }
    res
  }

  def renderMember(member: SymbLog): String = {
    toTypeTree(member.main, 1)
  }

  def toTypeTree(s: SymbolicRecord, n: Int): String = {
    var indent = ""
    for (i <- 1 to n) {
      indent = "  " + indent
    }
    var str = ""

    s match {
      case gb: GlobalBranchRecord => {
        str = str + gb.toTypeString + "\n"
        for (sub <- gb.thnSubs) {
          str = str + indent + toTypeTree(sub, n + 1)
        }
        for (sub <- gb.elsSubs) {
          str = str + indent + toTypeTree(sub, n + 1)
        }
      }
      case mc: MethodCallRecord => {
        str = str + "MethodCall\n"
        str = str + indent + "precondition\n"
        str = str + indent + "  " + toTypeTree(mc.precondition, n + 2)
        str = str + indent + "postcondition\n"
        str = str + indent + "  " + toTypeTree(mc.postcondition, n + 2)
        for (p <- mc.parameters) {
          str = str + indent + "parameter\n"
          str = str + indent + "  " + toTypeTree(p, n + 2)
        }
      }
      case _ => {
        str = s.toTypeString() + "\n"
        for (sub <- s.subs) {
          str = str + indent + toTypeTree(sub, n + 1)
        }
      }
    }
    str
  }
}


//=========== Records =========

sealed trait SymbolicRecord {
  val value: ast.Node
  val state: State
  // TODO: It would be nicer to use the PathConditionStack instead of the
  // Decider's internal representation for the pcs.
  // However, the recording happens to early such that the wrong
  // PathConditionStack Object is stored when using the PathConditionStack
  // TODO: Oops.
  val pcs: Set[Term]
  var subs = List[SymbolicRecord]()
  var lastFailedProverQuery: Option[Term] = None

  var startTimeMs: Long = 0
  var endTimeMs: Long = 0

  def toTypeString(): String

  override def toString(): String = {
    toTypeString() + " " + toSimpleString()
  }

  def toSimpleString(): String = {
    if (value != null) value.toString()
    else "null"
  }

  def toJson(): String = {
    if (value != null) JsonHelper.pair("value", value.toString())
    else ""
  }

  def dotNode(): String = {
    this.hashCode().toString()
  }

  def dotLabel(): String = {
    "\"" + this.toString() + "\""
  }
}

trait MemberRecord extends SymbolicRecord

trait MultiChildRecord extends SymbolicRecord

trait MultiChildOrderedRecord extends MultiChildRecord

trait MultiChildUnorderedRecord extends MultiChildRecord

trait SequentialRecord extends SymbolicRecord

class MethodRecord(v: ast.Method, s: State, p: PathConditionStack) extends MemberRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "method"
  }

  override def toSimpleString(): String = {
    if (value != null) value.name
    else "null"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("kind", "Method") + "," + JsonHelper.pair("value", value.name)
    else ""
  }
}

class PredicateRecord(v: ast.Predicate, s: State, p: PathConditionStack) extends MemberRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "predicate"
  }

  override def toSimpleString(): String = {
    if (value != null) value.name
    else "null"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("kind", "Predicate") + "," + JsonHelper.pair("value", value.name)
    else ""
  }
}

class FunctionRecord(v: ast.Function, s: State, p: PathConditionStack) extends MemberRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "function"
  }

  override def toSimpleString(): String = {
    if (value != null) value.name
    else "null"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("kind", "Function") + "," + JsonHelper.pair("value", value.name)
    else ""
  }
}

class ExecuteRecord(v: ast.Stmt, s: State, p: PathConditionStack) extends SequentialRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "execute"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("type", "execute") + "," + JsonHelper.pair("pos", utils.ast.sourceLineColumn(value)) + "," + JsonHelper.pair("value", value.toString())
    else ""
  }
}

class EvaluateRecord(v: ast.Exp, s: State, p: PathConditionStack) extends SequentialRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "evaluate"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("type", "evaluate") + "," + JsonHelper.pair("pos", utils.ast.sourceLineColumn(value)) + "," + JsonHelper.pair("value", value.toString())
    else ""
  }
}

class ProduceRecord(v: ast.Exp, s: State, p: PathConditionStack) extends SequentialRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "produce"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("type", "produce") + "," + JsonHelper.pair("pos", utils.ast.sourceLineColumn(value)) + "," + JsonHelper.pair("value", value.toString())
    else ""
  }
}

class ConsumeRecord(v: ast.Exp, s: State, p: PathConditionStack)
  extends SequentialRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "consume"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("type", "consume") + "," + JsonHelper.pair("pos", utils.ast.sourceLineColumn(value)) + "," + JsonHelper.pair("value", value.toString())
    else ""
  }
}

class WellformednessCheckRecord(v: Seq[ast.Exp], s: State, p: PathConditionStack)
  extends MultiChildUnorderedRecord {
  val value = null
  val conditions = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "WellformednessCheck"
  }

  override def toJson(): String = {
    JsonHelper.pair("kind", "WellformednessCheck")
  }
}

abstract class TwoBranchRecord(v: ast.Exp, s: State, p: PathConditionStack, env: String)
  extends MultiChildUnorderedRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null
  val environment = env

  var cond: SymbolicRecord = new CommentRecord("<missing condition>", null, null)
  var condEndTimeMs: Long = 0
  var thnSubs = List[SymbolicRecord](new CommentRecord("Unreachable", null, null))
  var thnExplored: Boolean = false
  var thnEndTimeMs: Long = 0
  var elsSubs = List[SymbolicRecord](new CommentRecord("Unreachable", null, null))
  var elsExplored: Boolean = false
  var elsEndTimeMs: Long = 0

  override def toSimpleString(): String = {
    if (value != null)
      value.toString()
    else
      toTypeString() + "<Null>"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("kind", toTypeString()) + "," + JsonHelper.pair("value", value.toString())
    else ""
  }

  override def toString(): String = {
    environment + " " + toSimpleString()
  }

  @elidable(INFO)
  def finish_cond(): Unit = {
    condEndTimeMs = System.currentTimeMillis()
    if (!subs.isEmpty)
      cond = subs(0)
    subs = List[SymbolicRecord]()
  }

  @elidable(INFO)
  def finish_thnSubs(): Unit = {
    thnExplored = true
    thnEndTimeMs = System.currentTimeMillis()
    if (!subs.isEmpty)
      thnSubs = subs
    subs = List[SymbolicRecord]()
  }

  @elidable(INFO)
  def finish_elsSubs(): Unit = {
    elsExplored = true
    elsEndTimeMs = System.currentTimeMillis()
    if (!subs.isEmpty)
      elsSubs = subs
    subs = List[SymbolicRecord]()
  }

  /**
    * hack such that endTimeMs is correctly set in the presence of initializeBranching, because identifier is not
    * in sepSet when collapsing the record
    */
  @elidable(INFO)
  def finish_record(): Unit = {
    if (endTimeMs == 0) {
      endTimeMs = System.currentTimeMillis()
    }
  }

  @elidable(INFO)
  def exploredBranchesCount(): Int = {
    (if (thnExplored) 1 else 0) + (if (elsExplored) 1 else 0)
  }
}

class GlobalBranchRecord(v: ast.Exp, s: State, p: PathConditionStack, env: String)
  extends TwoBranchRecord(v, s, p, env) {

  def toTypeString(): String = {
    "GlobalBranch"
  }
}

class LocalBranchRecord(v: ast.Exp, s: State, p: PathConditionStack, env: String)
  extends TwoBranchRecord(v, s, p, env) {

  def toTypeString(): String = {
    "LocalBranch"
  }
}

class CfgBranchRecord(v: Seq[SilverEdge], s: State, p: PathConditionStack, env: String)
  extends MultiChildUnorderedRecord {
  val value = null
  val state = s
  val pcs = if (p != null) p.assumptions else null
  val environment = env

  def toTypeString(): String = {
    "CfgBranch"
  }

  var branchSubs = List[List[SymbolicRecord]]()
  // ar branchEndTimesMs: List[Long] = List[Long]()

  override def toSimpleString(): String = {
    if (value != null)
      value.toString()
    else
      "CfgBranch<Null>"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("kind", "CfgBranch") + "," + JsonHelper.pair("value", value.toString())
    else ""
  }

  override def toString(): String = {
    environment + " " + toSimpleString()
  }

  @elidable(INFO)
  def finish_branchSubs(): Unit = {
    if (!subs.isEmpty)
      branchSubs = branchSubs ++ List(subs)
      // branchEndTimesMs = branchEndTimesMs ++ List(System.currentTimeMillis())
    subs = List[SymbolicRecord]()
  }
}

class ConditionalEdgeRecord(v: ast.Exp, s: State, p: PathConditionStack, env: String)
  extends MultiChildUnorderedRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null
  val environment = env

  def toTypeString(): String = {
    "ConditionalEdge"
  }

  var cond: SymbolicRecord = new CommentRecord("<missing condition>", null, null)
  var condEndTimeMs: Long = 0
  var thnSubs = List[SymbolicRecord](new CommentRecord("Unreachable", null, null))
  var thnEndTimeMs: Long = 0

  override def toSimpleString(): String = {
    if (value != null)
      value.toString()
    else
      "ConditionalEdge<Null>"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("kind", "ConditionalEdge") + "," + JsonHelper.pair("value", value.toString())
    else ""
  }

  override def toString(): String = {
    environment + " " + toSimpleString()
  }

  @elidable(INFO)
  def finish_cond(): Unit = {
    condEndTimeMs = System.currentTimeMillis()
    if (!subs.isEmpty)
      cond = subs(0)
    subs = List[SymbolicRecord]()
  }

  @elidable(INFO)
  def finish_thnSubs(): Unit = {
    thnEndTimeMs = System.currentTimeMillis()
    if (!subs.isEmpty)
      thnSubs = subs
    subs = List[SymbolicRecord]()
  }
}

class UnconditionalEdgeRecord(s: State, p: PathConditionStack, env: String) extends SequentialRecord {
  val value = null
  val state = s
  val pcs = if (p != null) p.assumptions else null
  val environment = env

  def toTypeString(): String = {
    "UnconditionalEdgeRecord"
  }

  override def toSimpleString(): String = {
    if (value != null)
      value.toString()
    else
      "UnconditionalEdgeRecord<Null>"
  }

  override def toJson(): String = {
    if (value != null) JsonHelper.pair("kind", "UnconditionalEdgeRecord") + "," + JsonHelper.pair("value", value.toString())
    else ""
  }

  override def toString(): String = {
    environment + " " + toSimpleString()
  }
}
class CommentRecord(str: String, s: State, p: PathConditionStack) extends SequentialRecord {
  val value = null
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "Comment"
  }

  val comment = str

  override def toSimpleString(): String = {
    if (comment != null) comment
    else "null"
  }

  override def toString(): String = {
    "comment: " + toSimpleString()
  }

  override def toJson(): String = {
    if (comment != null) JsonHelper.pair("value", comment)
    else ""
  }

  override def dotLabel(): String = {
    "\"" + comment + "\""
  }
}

class MethodCallRecord(v: ast.MethodCall, s: State, p: PathConditionStack)
  extends MultiChildOrderedRecord {
  val value = v
  val state = s
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "MethodCall"
  }

  var parameters = List[SymbolicRecord]()
  var precondition: SymbolicRecord = new ConsumeRecord(null, null, null)
  var postcondition: SymbolicRecord = new ProduceRecord(null, null, null)

  override def toString(): String = {
    if (value != null)
      "execute: " + value.toString()
    else
      "execute: MethodCall <null>"
  }

  override def toSimpleString(): String = {
    if (v != null) v.toString()
    else "MethodCall <null>"
  }

  override def toJson(): String = {
    if (v != null) JsonHelper.pair("kind", "MethodCall") + "," + JsonHelper.pair("value", v.toString())
    else ""
  }

  @elidable(INFO)
  def finish_parameters(): Unit = {
    parameters = subs // No check for emptyness. empty subs = no parameters, which is perfectly fine.
    subs = List[SymbolicRecord]()
  }

  @elidable(INFO)
  def finish_precondition(): Unit = {
    if (!subs.isEmpty)
      precondition = subs(0)
    subs = List[SymbolicRecord]()
  }

  @elidable(INFO)
  def finish_postcondition(): Unit = {
    if (!subs.isEmpty)
      postcondition = subs(0)
    subs = List[SymbolicRecord]()
  }
}

class DeciderAssertRecord(t: Term, timeout: Option[Int]) extends MemberRecord {
  val value: ast.Node = null
  val state: State = null
  val pcs: Set[Term] = null
  val term: Term = t
  val timeoutOptions: Option[Int] = timeout

  def toTypeString(): String = {
    "DeciderAssert"
  }

  override def toString(): String = {
    if (term != null)
      "Decider assert: " + term.toString()
    else
      "Decider assert: <null>"
  }

  override def toSimpleString(): String = {
    if (term != null) term.toString()
    else "DeciderAssert <null>"
  }
}

class ProverAssertRecord(t: Term, timeout: Option[Int]) extends MemberRecord {
  val value: ast.Node = null
  val state: State = null
  val pcs: Set[Term] = null
  val term: Term = t
  val timeoutOptions: Option[Int] = timeout

  def toTypeString(): String = {
    "ProverAssert"
  }

  override def toString(): String = {
    if (term != null)
      "Prover assert: " + term.toString()
    else
      "Prover assert: <null>"
  }

  override def toSimpleString(): String = {
    if (term != null) term.toString()
    else "ProverAssert <null>"
  }
}

class DeciderAssumeRecord(t: InsertionOrderedSet[Term]) extends MemberRecord {
  val value: ast.Node = null
  val state: State = null
  val pcs: Set[Term] = null
  val terms: InsertionOrderedSet[Term] = t

  def toTypeString(): String = {
    "DeciderAssume"
  }

  override def toString(): String = {
    if (terms != null)
      "Decider assume: " + terms.mkString(" ")
    else
      "Decider assume: <null>"
  }

  override def toSimpleString(): String = {
    if (terms != null) terms.mkString(" ")
    else "DeciderAssume <null>"
  }
}

class SingleMergeRecord(val destChunks: Seq[NonQuantifiedChunk], val newChunks: Seq[NonQuantifiedChunk],
                        p: PathConditionStack) extends MemberRecord {
  val value: ast.Node = null
  val state: State = null
  val pcs = if (p != null) p.assumptions else null

  def toTypeString(): String = {
    "SingleMerge"
  }

  override def toString(): String = {
    if (destChunks != null && newChunks != null)
      "Single merge: " + destChunks.mkString(" ") + " <= " + newChunks.mkString(" ")
    else
      "Single merge: <null>"
  }

  override def toSimpleString(): String = {
    if (destChunks != null && newChunks != null) (destChunks ++ newChunks).mkString(" ")
    else "SingleMerge <null>"
  }
}


class GenericNode(val label: String) {

  // ==== structural
  var children = List[GenericNode]()
  var successors = List[GenericNode]()
  // ==== structural

  var isSyntactic: Boolean = false
  var isSmtQuery: Boolean = false
  var startTimeMs: Long = 0
  var endTimeMs: Long = 0

  override def toString(): String = {
    label
  }
}

class GenericNodeRenderer extends Renderer[SymbLog, GenericNode] {

  def render(memberList: List[SymbLog]): GenericNode = {
    var children: List[GenericNode] = List()
    for (m <- memberList) {
      children = children ++ List(renderMember(m))
    }
    var startTimeMs: Long = 0
    var endTimeMs: Long = 0
    for (m <- memberList) {
      if (m.main != null) {
        if (startTimeMs == 0 || m.main.startTimeMs < startTimeMs) {
          startTimeMs = m.main.startTimeMs
        }
        if (m.main.endTimeMs > endTimeMs) {
          endTimeMs = m.main.endTimeMs
        }
      }
    }
    val node = new GenericNode("Members")
    node.startTimeMs = startTimeMs
    node.endTimeMs = endTimeMs
    node.children = children
    node
  }

  def renderMember(s: SymbLog): GenericNode = {
    /*
    // adjust start and end time of s.main:
    // TODO this should not be necessary!
    if (s.main.subs.nonEmpty) {
      s.main.startTimeMs = s.main.subs.head.startTimeMs
      s.main.endTimeMs = s.main.subs.last.endTimeMs
    }
    */
    renderRecord(s.main)
  }

  def renderRecord(r: SymbolicRecord): GenericNode = {

    var node = new GenericNode(r.toString())
    node.startTimeMs = r.startTimeMs
    node.endTimeMs = r.endTimeMs
    // set isSmtQuery flag:
    r match {
      case sq: ProverAssertRecord => node.isSmtQuery = true
      case _ =>
    }

    // TODO replace CondExpr with corresponding Branch and Join records
    r match {
      case cbRecord: CfgBranchRecord => {
        // branches are successors
        for (branchSubs <- cbRecord.branchSubs) {
          if (branchSubs.length != 1) {
            throw new AssertionError("each branch should only have one sub which should be a ConditionalEdgeRecord")
          }
          val branchNode = renderRecord(branchSubs.head)
          node.successors = node.successors ++ List(branchNode)
        }
        node.isSyntactic = true
        // end time corresponds to the start time of the first branch
        for (successor <- node.successors) {
          if (successor.startTimeMs < node.endTimeMs) {
            node.endTimeMs = successor.startTimeMs
          }
        }
      }

      case ceRecord: ConditionalEdgeRecord => {
        // insert condition as child
        node.children = node.children ++ List(renderRecord(ceRecord.cond))
        // the end time corresponds to the end time of the condition:
        node.endTimeMs = ceRecord.condEndTimeMs
        // stmts of the basic blocks (following the condition) are attached as a single branch node to the successors
        val branchNode = renderBranch(ceRecord.thnSubs, ceRecord.condEndTimeMs, ceRecord.thnEndTimeMs)
        node.successors = node.successors ++ List(branchNode)
      }

      case ueRecord: UnconditionalEdgeRecord => {
        node.isSyntactic = true
        for (sub <- r.subs) {
          node.children = node.children ++ List(renderRecord(sub))
        }
      }

      case gb: GlobalBranchRecord => {
        // insert condition as child
        node.children = node.children ++ List(renderRecord(gb.cond))
        // the end time corresponds to the end time of the condition:
        node.endTimeMs = gb.condEndTimeMs
        // if and else branch are two successors
        val thnNode = renderBranch(gb.thnSubs, gb.condEndTimeMs, gb.thnEndTimeMs)
        val elsNode = renderBranch(gb.elsSubs, gb.thnEndTimeMs, gb.elsEndTimeMs)
        node.successors = node.successors ++ List(thnNode, elsNode)
      }

      case lb: LocalBranchRecord => {
        // node is the branch node having then and else nodes as successors.
        // then and else nodes have themselves the join node as successor
        node.children = node.children ++ List(renderRecord(lb.cond))
        // the end time corresponds to the end time of the condition:
        node.endTimeMs = lb.condEndTimeMs
        // if and else branch are two successors
        val thnNode = renderBranch(lb.thnSubs, lb.condEndTimeMs, lb.thnEndTimeMs)
        val elsNode = renderBranch(lb.elsSubs, lb.thnEndTimeMs, lb.elsEndTimeMs)
        node.successors = node.successors ++ List(thnNode, elsNode)
        // assign same node as successor of thnNode and elsNode:
        var joinNode = new GenericNode("Join Point")
        // TODO get join duration
        joinNode.startTimeMs = lb.elsEndTimeMs
        joinNode.endTimeMs = lb.endTimeMs
        thnNode.successors = thnNode.successors ++ List(joinNode)
        elsNode.successors = elsNode.successors ++ List(joinNode)
      }

      case _ => {
        for (sub <- r.subs) {
          node.children = node.children ++ List(renderRecord(sub))
        }
      }
    }

    node
  }

  def renderBranch(branchSubs: List[SymbolicRecord], branchStartTimeMs: Long, branchEndTimeMs: Long): GenericNode = {
    val branchNode = new GenericNode("Branch")
    branchNode.startTimeMs = branchStartTimeMs
    branchNode.endTimeMs = branchEndTimeMs
    branchNode.isSyntactic = true
    for (sub <- branchSubs) {
      branchNode.children = branchNode.children ++ List(renderRecord(sub))
    }
    branchNode
  }
}

class ChromeTraceRenderer extends Renderer[GenericNode, String] {

  def render(memberList: List[GenericNode]): String = {
    val renderedMembers: Iterable[String] = memberList map renderMember
    "[" + renderedMembers.mkString(",") + "]"
  }

  // creates an event json object for each node
  // {"name": "Asub", "cat": "PERF", "ph": "B", "pid": 22630, "tid": 22630, "ts": 829}
  def renderMember(n: GenericNode): String = {
    val renderedChildren = (n.children map renderMember) filter(p => p != null && p!= "")
    val renderedSuccessors = (n.successors map renderMember) filter(p => p != null && p!= "")
    val renderedNode = renderNode(n)
    if (renderedNode == null) {
      println("skipping node " + n.toString())
      return ""
    }
    (List(renderedNode) ++ renderedChildren ++ renderedSuccessors).mkString(",")
  }

  // renders a node without considering its children or successors
  def renderNode(n: GenericNode): String = {
    if (n.startTimeMs == 0 || n.endTimeMs == 0) {
      return null
    }
    // start event
    "{" + JsonHelper.pair("name", n.label) + "," +
      JsonHelper.pair("cat", "PERF") + "," +
      JsonHelper.pair("ph", "B") + "," +
      JsonHelper.pair("pid", 1) + "," +
      JsonHelper.pair("tid", 1) + "," +
      JsonHelper.pair("ts", n.startTimeMs) + "}," +
    // end event
      "{" + JsonHelper.pair("name", n.label) + "," +
      JsonHelper.pair("cat", "PERF") + "," +
      JsonHelper.pair("ph", "E") + "," +
      JsonHelper.pair("pid", 1) + "," +
      JsonHelper.pair("tid", 1) + "," +
      JsonHelper.pair("ts", n.endTimeMs) + "}"
  }
}

class JsonRenderer extends Renderer[GenericNode, String] {
  // visit all nodes and insert them into an array such that each node can be referenced by its index
  var nodes: List[GenericNode] = List()

  override def render(memberList: List[GenericNode]): String = {
    memberList foreach buildHierarchy

    val renderedMembers: Iterable[String] = nodes map renderMember
    "[" + renderedMembers.mkString(",") + "]"
  }

  def buildHierarchy(n: GenericNode): Unit = {
    // add node to list of all nodes:
    if (!nodes.contains(n)) {
      nodes = nodes ++ List(n)
    }

    n.children foreach buildHierarchy
    n.successors foreach buildHierarchy
  }

  override def renderMember(n: GenericNode): String = {
    val childrenIndices = n.children map nodes.indexOf
    val successorsIndices = n.successors map nodes.indexOf
    if (childrenIndices.contains(-1) || successorsIndices.contains(-1)) {
      println("unresolved node found; skipping node " + n.toString())
      return "{" + JsonHelper.pair("label", n.label) + "}"
    }
    "{" + JsonHelper.pair("id", nodes.indexOf(n)) + "," +
      JsonHelper.pair("label", n.label) + "," +
      JsonHelper.pair("isSmtQuery", n.isSmtQuery) + "," +
      JsonHelper.pair("isSyntactic", n.isSyntactic) + "," +
      JsonHelper.pair("startTimeMs", n.startTimeMs) + "," +
      JsonHelper.pair("endTimeMs", n.endTimeMs) + "," +
      JsonHelper.pair("children", childrenIndices) + "," +
      JsonHelper.pair("successors", successorsIndices) + "}"
  }
}


/**
  * ================================
  * GUIDE FOR ADDING RECORDS TO SymbExLogger
  * ================================
  *
  * SymbExLogger records all calls of the four symbolic primitives Execute, Evaluate, Produce
  * and Consume. By default, only the current state, context and parameters of the primitives are stored.
  * If you want to get more information from certain structures, there are several ways to store additional
  * info:
  *
  * 1) Store the information as a CommentRecord.
  * 2) Implement a customized record.
  *
  * Use of CommentRecord (1):
  * At the point in the code where you want to add the comment, call
  * //SymbExLogger.currentLog().insert(new CommentRecord(my_info, σ, c)
  * //SymbExLogger.currentLog().collapse()
  * σ is the current state (AnyRef, but should be of type State[_,_,_] usually), my_info
  * is a string that contains the information you want to store, c is the current
  * context. If you want to store more information than just a string, consider (2).
  *
  * Use of custom Records (2):
  * For already existing examples, have a look at CondExpRecord (local Branching) or IfThenElseRecord
  * (recording of If-Then-Else-structures).
  * Assume that you want to have a custom record for  (non-short-circuiting) evaluations of
  * ast.And, since you want to differ between the evaluation of the lefthandside
  * and the righthandside (not possible with default recording).
  * Your custom record could look similar to this:
  *
  * class AndRecord(v: ast.And, s: AnyRef, c: DefaultContext) extends SymbolicRecord {
  * val value = v    // Due to inheritance. This is what gets recorded by default.
  * val state = s    // "
  * val context = c  // "
  *
  * lhs: SymbolicRecord = new CommentRecord("null", null, null)
  * rhs: SymbolicRecord = new CommentRecord("null", null, null)
  * // lhs & rhs are what you're interested in. The first initialization should be overwritten anyway,
  * // initialization with a CommentRecord just ensures that the logger won't crash due
  * // to a Null Exception (ideally). Can also be used if you're unsure if a certain structure is
  * // evaluated at all; e.g., the righthandside might not be evaluated because the lefthandside
  * // is already false (see IfThenElseRecord: paths might be unreachable, so the default is
  * // a CommentRecord("Unreachable", null, null) which is not overwritten due to unreachability.
  *
  * def finish_lhs(): Unit = {
  * if(!subs.isEmpty) //so you don't overwrite your default CommentRecord if subs is empty
  * lhs = subs(0)
  * subs = List[SymbolicRecord]()
  * }
  *
  * def finish_rhs(): Unit = {
  * if(!subs.isEmpty)
  * rhs = subs(0)
  * subs = List[SymbolicRecord]()
  * }
  *
  * // finish_FIELD() is the method that overwrites the given field with what is currently in 'subs'.
  * // For usage example, see below.
  * }
  *
  * Usage of your new custom record 'AndRecord':
  * This is the code in the DefaultEvaluator (after unrolling of evalBinOp):
  *
  * case ast.And(e0, e1) if config.disableShortCircuitingEvaluations() =>
  * eval(σ, e0, pve, c)((t0, c1) =>
  * eval(σ, e1, pve, c1)((t1, c2) =>
  * Q(And(t0, t1), c2)))
  *
  * Use of the new record:
  *
  * case and @ ast.And(e0, e1) if config.disableShortCircuitingEvaluations() =>
  * andRec = new AndRecord(and, σ, c)
  * SymbExLogger.currentLog().insert(andRec)
  * eval(σ, e0, pve, c)((t0, c1) => {
  * andRec.finish_lhs()
  * eval(σ, e1, pve, c1)((t1, c2) => {
  * andRec.finish_rhs()
  * SymbExLogger.currentLog().collapse()
  * Q(And(t0, t1), c2)))}}
  *
  * The record is now available for use; now its representation needs to be implemented,
  * which is done Renderer-Classes. Implement a new case in all renderer for the new
  * record.
  */

class SymbExLogUnitTest(f: Path) {
  private val originalFilePath: Path = f
  private val fileName: Path = originalFilePath.getFileName()

  /**
    * If there is a predefined 'expected-output'-file (.elog) for the currently verified program,
    * a 'actual output'-file is created (.alog) and then compared with the expected output. Should
    * only be called if the whole verification process is already terminated.
    */
  def verify(): Seq[SymbExLogUnitTestError] = {
    val expectedPath = Paths.get("src/test/resources/symbExLogTests/" + fileName + ".elog").toString()
    val actualPath = Paths.get("src/test/resources/symbExLogTests/" + fileName + ".alog").toString()
    var errorMsg = ""
    var testFailed = false
    val testIsExecuted = Files.exists(Paths.get(expectedPath))

    if (testIsExecuted) {
      val pw = new java.io.PrintWriter(new File(actualPath))
      try pw.write(SymbExLogger.toSimpleTreeString) finally pw.close()

      val expectedSource = scala.io.Source.fromFile(expectedPath)
      val expected = expectedSource.getLines()

      val actualSource = scala.io.Source.fromFile(actualPath)
      val actual = actualSource.getLines()

      var lineNumber = 0

      while (!testFailed && expected.hasNext && actual.hasNext) {
        if (!actual.next().equals(expected.next())) {
          testFailed = true
        }
        lineNumber = lineNumber + 1
      }
      if (actual.hasNext != expected.hasNext)
        testFailed = true

      if (testFailed) {
        errorMsg = errorMsg + "Unit Test failed, expected output "
        errorMsg = errorMsg + "does not match actual output. "
        errorMsg = errorMsg + "First occurrence at line " + lineNumber + ".\n"
        errorMsg = errorMsg + "Compared Files:\n"
        errorMsg = errorMsg + "expected: " + expectedPath.toString() + "\n"
        errorMsg = errorMsg + "actual:   " + actualPath.toString() + "\n"
      }

      val execTimeOutput = SymbExLogger.checkMemberList()
      if (execTimeOutput != "") {
        testFailed = true
        errorMsg = errorMsg + "ExecTimeChecker: " + execTimeOutput + "\n"
      }

      actualSource.close()
      expectedSource.close()
    }
    if (testIsExecuted && testFailed) {
      Seq(new SymbExLogUnitTestError(errorMsg))
    }
    else {
      Nil
    }
  }
}

case class SymbExLogUnitTestError(msg: String) extends AbstractError {
  def pos = ast.NoPosition

  def fullId = "symbexlogunittest.error"

  def readableMessage = msg
}
