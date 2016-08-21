package viper
package silicon

import java.io.File
import java.nio.file.{Path, Paths, Files}
import state.{MapBackedStore, ListBackedHeap, DefaultState, DefaultContext}
import viper.silver.ast.NoPosition
import viper.silver.verifier.AbstractError

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

  /** Flag; if disabled, no output files for visualisations are created.**/
  val FLAG_WRITE_FILES = false

  /** Config of Silicon. Used by StateFormatters.**/
  private var config: Config = null

  /** Add a new log for a method, function or predicate (member).
   *
   * @param member Either a MethodRecord, PredicateRecord or a FunctionRecord.
   * @param s Current state. Since the body of the method (predicate/function) is not yet
   *          executed/logged, this is usually the empty state (use Σ(Ø, Ø, Ø) for empty
   *          state).
   * @param c Current context.
   */
  def insertMember(member: silver.ast.Member, s: AnyRef, c: DefaultContext): Unit ={
    memberList = memberList ++ List(new SymbLog(member, s, c))
  }

  /** Use this method to access the current log, e.g., to access the log of the method
    * that gets currently symbolically executed.
   *
   * @return Returns the current method, predicate or function that is being logged.
   */
  def currentLog(): SymbLog = {
    memberList.last
  }

  /**
   * Passes config from Silicon to SymbExLogger.
   * Config is assigned only once, further calls are ignored.
    *
    * @param c Config of Silicon.
   */
  def setConfig(c: Config): Unit = {
    if(config == null)
      config = c
  }

  /** Gives back config from Silicon **/
  def getConfig(): Config = {
    config
  }

  /**
   * Simple string representation of the logs.
   */
  def toSimpleTreeString(): String = {
    val simpleTreeRenderer = new SimpleTreeRenderer()
    simpleTreeRenderer.render(memberList)
  }

  def toIdeHierarchy(): String = {
    val renderer = new IdeHierarchyRenderer()
    renderer.render(memberList)
  }

  /**
   * Simple string representation of the logs, but contains only the types of the records
   * and not their values. Original purpose was usage for unit testing.
   */
  def toTypeTreeString(): String = {
    val typeTreeRenderer = new TypeTreeRenderer()
    typeTreeRenderer.render(memberList)
  }
  /**
   * Writes a .DOT-file with a representation of all logged methods, predicates, functions.
   * DOT-file can be interpreted with GraphViz (http://www.graphviz.org/)
   */
  def writeDotFile(): Unit = {
    if(FLAG_WRITE_FILES || config.ideMode()) {
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
  def writeJSFile(): Unit = {
    if(FLAG_WRITE_FILES || config.ideMode()) {
      val jsRenderer = new JSTreeRenderer()
      val pw = new java.io.PrintWriter(new File(getOutputFolder() + "executionTreeData.js"))
      try pw.write(jsRenderer.render(memberList)) finally pw.close()
    }
  }

  def writeSimpleTreeFile(): Unit = {
    if(FLAG_WRITE_FILES) {
      val renderer = new SimpleTreeRenderer()
      val pw = new java.io.PrintWriter(new File(".vscode/simpleTree.txt"))
      try pw.write(renderer.render(memberList)) finally pw.close()
    }
  }

  def writeIdeHierarchyFile(): Unit = {
    if(config.ideMode()) {
      val renderer = new IdeHierarchyRenderer()
      val pw = new java.io.PrintWriter(new File(getOutputFolder() + "ideHierarchy.txt"))
      try pw.write(renderer.render(memberList)) finally pw.close()
    }
  }

  def writeTypeTreeFile(): Unit = {
    if(FLAG_WRITE_FILES) {
      val renderer = new TypeTreeRenderer()
      val pw = new java.io.PrintWriter(new File(getOutputFolder() + "typeTree.txt"))
      try pw.write(renderer.render(memberList)) finally pw.close()
    }
  }
  
  def getOutputFolder():String = {
    if(config.ideMode()){
        ".vscode/"
      }else{
        "utils/symbolicRecording/"
      }
  }

  /** Path to the file that is being executed. Is used for UnitTesting. **/
  var filePath: Path = null
  /** Unit Testing **/
  var unitTestEngine: SymbExLogUnitTest = null
  /** Initialize Unit Testing. Should be done AFTER the file to be tested is known. **/
  def initUnitTestEngine(): Unit = {
    if(filePath != null)
      unitTestEngine = new SymbExLogUnitTest(filePath)
  }

  /**
   * Resets the SymbExLogger-object, to make it ready for a new file.
   * Only needed when several files are verified together (e.g., sbt test).
   */
  def reset(): Unit ={
    memberList = List[SymbLog]()
    filePath = null
    unitTestEngine = null
    config = null
  }
}

//========================= SymbLog ========================

/**
 * Concept: One object of SymbLog per Method/Predicate/Function. SymbLog
 * is used in the SymbExLogger-object.
 */
class SymbLog(v: silver.ast.Member, s: AnyRef, c: DefaultContext) {
  var main = v match {
    case m: silver.ast.Method     => new MethodRecord(m, s, c)
    case p: silver.ast.Predicate  => new PredicateRecord(p, s, c)
    case f: silver.ast.Function   => new FunctionRecord(f,s, c)
  }

  private var stack = List[SymbolicRecord](main)
  private var sepCounter = 0
  private var sepSet = Set[Int]()

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

    if(!isUsed(s.value) || isRecordedDifferently(s))
      return -1

    current().subs = current().subs++List(s)
    stack = s::stack

    sepCounter = sepCounter+1
    sepSet = sepSet+sepCounter
    return sepCounter
  }

  /**
   * 'Finishes' the recording at the current node and goes one level higher in the record tree.
   * There should be only one call of collapse per insert.
    *
    * @param v The node that will be 'collapsed'. Is only used for filtering-purposes, can be null.
   * @param n The identifier of the node (can NOT be null). The identifier is created by insert (return
   *          value).
   */
  def collapse(v: silver.ast.Node, n: Int): Unit = {
    if(n != -1 && sepSet.contains(n)) {
      sepSet = sepSet - n
      if (isUsed(v))
        stack = stack.tail
    }
  }

  /**
   * Quite a hack. Is used for impure Branching, where 'redundant' collapses in the continuation
   * can mess up the logging-hierarchy. Idea: Just remove all identifiers from the collapse-Set, so
   * collapse will NOT collapse records that were inserted outside of branching but collapsed inside
   * a branch due to continuation. Currently, this is only used for impure Branching (CondExp/Implies
   * in Producer/Consumer).
   */
  def initializeBranching(): Unit = {
    sepSet = Set[Int]()
  }

  /**
   * Quite a hack, similar purpose as initializeBranching. Is used to make sure that an else-branch
   * is logged correctly, which is sometimes not the case in branching when collapses from the continuation
   * in the If-branch remove the branching-record itself from the stack. Currently only used for impure
   * Branching (CondExp/Implies in Producer/Consumer).
    *
    * @param s Record that should record the else-branch.
   */
  def prepareOtherBranch(s: SymbolicRecord): Unit = {
    stack = s::stack
  }

  private def isRecordedDifferently(s: SymbolicRecord): Boolean = {
    s.value match {
      case v: silver.ast.MethodCall =>
        s match {
          case _: MethodCallRecord => false
          case _ => true
        }
      case v: silver.ast.CondExp =>
        s match {
          case _: EvaluateRecord | _: ConsumeRecord | _: ProduceRecord => true
          case _ => false
        }
      case v: silver.ast.Implies =>
        s match {
          case _: ConsumeRecord | _: ProduceRecord => true
          case _ => false
        }

      case _ => false
    }
  }

  private def isUsed(node: silver.ast.Node): Boolean = {
    node match {
      case stmt: silver.ast.Stmt => {
        stmt match {
          case _: silver.ast.Seqn =>
            return false
          case _ =>
            return true
        }
      }
      case _ => return true
    }
  }
}

//===== Renderer Classes =====

sealed trait Renderer[T] {
  def renderMember(s: SymbLog): T
  def render(memberList: List[SymbLog]): T
}

class DotTreeRenderer extends Renderer[String] {

  def render(memberList: List[SymbLog]): String = {
    var str: String = "digraph {\n"
    str = str + "node [shape=rectangle];\n\n";

    for(m <- memberList) {
      str = str + renderMember(m) + "\n\n"
    }

    str = str + "}"
    str
  }

  def renderMember(s: SymbLog): String = {
    val main = s.main
    var output = ""

    output += "    "+main.dotNode()+" [label="+main.dotLabel()+"];\n"
    output += subsToDot(main)
    output
  }

  private var previousNode = "";
  private var unique_node_nr = this.hashCode();

  private def unique_node_number():Int = {
    unique_node_nr = unique_node_nr+1
    unique_node_nr
  }

  private def subsToDot(s: SymbolicRecord): String = {
    previousNode = s.dotNode()

    var output = ""

    s match {
      case ite: IfThenElseRecord => {
        val ite_parent = previousNode
        output += "    " + ite.thnCond.dotNode() + " [label=" + ite.thnCond.dotLabel() + "];\n"
        output += "    " + previousNode + " -> " + ite.thnCond.dotNode() + ";\n"

        // Activate either this or the next line (uncomment). If you use the first, the
        // representation will not show the evaluation of the if-condition, knowing that
        // it results in true anyway since the True-Branch is taken. If you want to see
        // the whole representation without any simplification, use the second line with
        // 'subsToDot(ite.thnCond).

        // previousNode = ite.thnCond.dotNode()
        output += subsToDot(ite.thnCond)

        for (rec <- ite.thnSubs) {
          output += "    " + rec.dotNode() + " [label=" + rec.dotLabel() + "];\n"
          output += "    " + previousNode + " -> " + rec.dotNode() + ";\n"
          output += subsToDot(rec)
        }
        previousNode = ite_parent
        output += "    " + ite.elsCond.dotNode() + " [label=" + ite.elsCond.dotLabel() + "];\n"
        output += "    " + previousNode + " -> " + ite.elsCond.dotNode() + ";\n"

        // Same as above.
        // previousNode = ite.elsCond.dotNode()
        output += subsToDot(ite.elsCond)

        for (rec <- ite.elsSubs) {
          output += "    " + rec.dotNode() + " [label=" + rec.dotLabel() + "];\n"
          output += "    " + previousNode + " -> " + rec.dotNode() + ";\n"
          output += subsToDot(rec)
        }
      }
      case ce: CondExpRecord => {

        output += "    " + ce.cond.dotNode() + " [label="+ce.cond.dotLabel()+"];\n"
        output += "    " + previousNode + " -> " + ce.cond.dotNode() + ";\n"
        previousNode = ce.cond.dotNode()

        output += "    " + ce.thnExp.dotNode() + " [label="+ce.thnExp.dotLabel()+"];\n"
        output += "    " + previousNode + " -> " + ce.thnExp.dotNode() + ";\n"
        output += subsToDot(ce.thnExp)
        val thnExp_end = previousNode

        previousNode = ce.cond.dotNode()
        output += "    " + ce.elsExp.dotNode() + " [label="+ce.elsExp.dotLabel()+"];\n"
        output += "    " + previousNode + " -> " + ce.elsExp.dotNode() + ";\n"
        output += subsToDot(ce.elsExp)
        val elsExp_end = previousNode

        val join_node = unique_node_number().toString()
        output += "    " + join_node + " [label=\"Join\"];\n"
        output += "    " + thnExp_end + " -> " + join_node + ";\n"
        output += "    " + elsExp_end + " -> " + join_node + ";\n"
        previousNode = join_node
      }
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
        output += "    " + mc.dotNode() + " [label="+mc.dotLabel()+"];\n"
        previousNode = mc.dotNode()

        for(p <- mc.parameters){
          output += "    " + p.dotNode() + " [label=\"parameter: "+p.toSimpleString()+"\"];\n"
          output += "    " + previousNode + " -> " + p.dotNode() + ";\n"
          output += subsToDot(p)
        }
        previousNode = mc.dotNode()

        output += "    " + mc.precondition.dotNode() + " [label=\"precondition: "+mc.precondition.toSimpleString()+"\"];\n"
        output += "    " + previousNode + " -> " + mc.precondition.dotNode() + ";\n"
        output += subsToDot(mc.precondition)
        previousNode = mc.dotNode()

        output += "    " + mc.postcondition.dotNode() + " [label=\"postcondition: "+mc.postcondition.toSimpleString()+"\"];\n"
        output += "    " + previousNode + " -> " + mc.postcondition.dotNode() + ";\n"
        output += subsToDot(mc.postcondition)
        previousNode = mc.dotNode()


      }
      case _ => {
        if(s.subs.isEmpty)
          return ""
        for(rec <- s.subs) {
          output += "    " + rec.dotNode() + " [label=" + rec.dotLabel() + "];\n"
          output += "    " + previousNode + " -> " + rec.dotNode() + ";\n"
          output += subsToDot(rec)
        }
      }
    }

    return output
  }
}

class JSTreeRenderer extends Renderer[String] {

  val stateFormatter: DefaultStateFormatter[MapBackedStore,
    ListBackedHeap, DefaultState[MapBackedStore, ListBackedHeap]]
  = new DefaultStateFormatter[MapBackedStore, ListBackedHeap,
    DefaultState[MapBackedStore, ListBackedHeap]](SymbExLogger.getConfig())

  def render(memberList: List[SymbLog]): String = {
    var str = "var executionTreeData = [\n"
    str += memberList.map(s => renderMember(s)).fold(""){(a,b) => if(a == "")b else a+", \n"+b}+ "]\n"
    return str
  }

  def renderMember(member: SymbLog): String = {
    val main = member.main
    var output = ""
    output += recordToJS(main) + "\n"
    return output
  }

  private def recordToJS(s: SymbolicRecord): String = {
    var output = ""

    val name = "\"name\"";
    val open = "\"open\"";
    val prestate = "\"prestate\"";
    val children = "\"children\"";
    val store = "\"store\"";
    val heap = "\"heap\"";
    val pcs = "\"pcs\"";

    s match {
      case ite: IfThenElseRecord => {
        output += "{"+name+": \"IfThenElse\","+open+": true, "+prestate+": "
        output += "{"+store+": \"\","+heap+": \"\","+pcs+": \"\"}"
        output += "\n,"+children+": [\n"
        output += "{"+name+": \"if " +ite.thnCond.toSimpleString()+ "\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(ite.thnCond) + "\","+heap+": \"\","+pcs+": \"\"}"
        output += ",\n"+children+": [\n"
        output += combine(ite.thnSubs)
        output += "]},\n"
        output += "{"+name+": \"else " +ite.elsCond.toSimpleString()+ "\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(ite.elsCond) + "\","+heap+": \"\","+pcs+": \"\"}"
        output += ",\n"+children+": [\n"
        output += combine(ite.elsSubs)
        output += "]}\n"
        output += "]}"
      }
      case ce: CondExpRecord => {
        output += "{"+name+": \""+ce.toString()+"\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(ce) + "\","+heap+": \"\","+pcs+": \"\"}"
        output += "\n,"+children+": [\n"
        output += recordToJS(ce.thnExp) + ", \n"
        output += recordToJS(ce.elsExp) + "]}"
      }
      case gb: GlobalBranchRecord => {
        output += "{"+name+": \""+gb.toString()+"\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(gb) + "\","+heap+": \"\","+pcs+": \"\"}"
        output += "\n,"+children+": [\n"
        output += "{"+name+": \"Branch 1: " + "\","+open+": true,"+prestate+": "
        output += "{"+store+": \"\","+heap+": \"\","+pcs+": \"\"}"
        output += ",\n"+children+": [\n"
        output += combine(gb.thnSubs)
        output += "]},\n"
        output += "{"+name+": \"Branch 2: " + "\","+open+": true,"+prestate+": "
        output += "{"+store+": \"\","+heap+": \"\","+pcs+": \"\"}"
        output += ",\n"+children+": [\n"
        output += combine(gb.elsSubs)
        output += "]}\n"
        output += "]}"
      }
      case mc: MethodCallRecord => {
        output += "{"+name+": \""+mc.toString()+"\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(mc) + "\","+heap+": \"\","+pcs+": \"\"}"
        output += "\n,"+children+": [\n"

        output += "{"+name+": \"parameters\","+open+": true,"+prestate+": "
        output += "{"+store+": \"\","+heap+": \"\","+pcs+": \"\"}"
        output += "\n,"+children+": [\n"
        output += combine(mc.parameters)
        output += "]},"

        output += "{"+name+": \"precondition\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(mc.precondition) + "\","+heap+": \"\","+pcs+": \"\"}"
        output += "\n,"+children+": [\n"
        output += recordToJS(mc.precondition)
        output += "]},"

        output += "{"+name+": \"postcondition\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(mc.postcondition) + "\","+heap+": \"\","+pcs+": \"\"}"
        output += "\n,"+children+": [\n"
        output += recordToJS(mc.postcondition)
        output += "]}"

        output += "]}"
      }
      case cr: CommentRecord => {
        output += "{"+name+": \""+cr.toString+"\","+open+": true,"+prestate+": "
        output += "{"+store+": \"\","+heap+": \"\","+pcs+": \"\"}}"
      }
      case _ => {
        output += "{"+name+": \"" + s.toString() + "\","+open+": true,"+prestate+": "
        output += "{"+store+": \"" + printState(s) + "\","+heap+": \"\","+pcs+": \"\"}"
        if (!s.subs.isEmpty) {
          output += ",\n"+children+": [\n"
          output += combine(s.subs)
          output += "]"
        }
        output += "}"
      }
    }
    return output
  }

  def combine(list:List[SymbolicRecord]):String = {
    list.map(s => recordToJS(s)).fold(""){(a,b) => if(a == "")b else a+", \n"+b} + "\n"
  }

  def printState(s: SymbolicRecord): String = {
    var res = ""
    if (s.state != null) {
      res = stateFormatter.format(s.state.asInstanceOf[DefaultState[MapBackedStore, ListBackedHeap]])
      var i=0
      while(i < res.length()) {
        if (res(i).equals('\n')) {
          res = res.substring(0, i-1) + "\\n" + res.substring(i+1, res.length())
          i=i+1
        }
        i = i+1
      }
    }
    res
  }
}

class SimpleTreeRenderer extends Renderer[String] {
  def render(memberList: List[SymbLog]): String = {
    var res = ""
    for (m <- memberList){
      res = res + renderMember(m) + "\n"
    }
    res
  }
  
  def renderMember(member: SymbLog): String = {
    toSimpleTree(member.main, 1)
  }
  
  def toSimpleTree(s: SymbolicRecord, n: Int): String = {
    var indent = ""
    for(i <- 1 to n) {
      indent = "  " + indent
    }
    var str = ""
    s match {
      case ite: IfThenElseRecord => {
        str = str + "if " + ite.thnCond.toSimpleString()+"\n"
        str = str + indent + toSimpleTree(ite.thnCond, n+1)
        for(sub <- ite.thnSubs){
          str = str + indent + toSimpleTree(sub, n+1)
        }
        str = str + indent.substring(2) + "else " + ite.elsCond.toSimpleString()+"\n"
        str = str + indent + toSimpleTree(ite.elsCond, n+1)
        for(sub <- ite.elsSubs){
          str = str + indent + toSimpleTree(sub, n+1)
        }
      }
      case ce: CondExpRecord => {
        str = str + ce.toString()+"\n"
        str = str + indent + toSimpleTree(ce.thnExp, n+1)
        str = str + indent + toSimpleTree(ce.elsExp, n+1)
        return str
      }
      case gb: GlobalBranchRecord => {
        str = str + "Branch 1:\n"
        for(sub <- gb.thnSubs){
          str = str + indent + toSimpleTree(sub, n+1)
        }

        str = str + indent.substring(2) + "Branch 2:\n"
        for(sub <- gb.elsSubs){
          str = str + indent + toSimpleTree(sub, n+1)
        }
      }
      case mc: MethodCallRecord => {
        str = str + mc.toString()+"\n"
        str = str + indent + "precondition: " + toSimpleTree(mc.precondition, n+1)
        str = str + indent + "postcondition: " + toSimpleTree(mc.postcondition, n+1)
        for(p <- mc.parameters) {
          str = str + indent + "parameter: " + toSimpleTree(p, n+1)
        }
      }
      case _ => {
        str = str + s.toString() + "\n"
        for (sub <- s.subs) {
          str = str + indent + toSimpleTree(sub, n + 1)
        }
      }
    }
    return str
  }
}

class IdeHierarchyRenderer extends Renderer[String] {
  def render(memberList: List[SymbLog]): String = {
    var res = ""
    for (m <- memberList){
      res = res + renderMember(m) + "\n"
    }
    res
  }

  def renderMember(member: SymbLog): String = {
    toIdeHierarchy(member.main, 1)
  }

  def toIdeHierarchy(s: SymbolicRecord, n: Int): String = {
    var indentWithoutNumber = ""
    for(i <- 1 to n) {
      indentWithoutNumber = indentWithoutNumber + "  "
    }
    var indent = n+indentWithoutNumber;

    var str = ""
    s match {
      case ite: IfThenElseRecord => {
        str = str + "if: " + ite.thnCond.toSimpleString()+"\n"
        str = str + indent + toIdeHierarchy(ite.thnCond, n+1)
        for(sub <- ite.thnSubs){
          str = str + indent + toIdeHierarchy(sub, n+1)
        }
        str = str + n+indentWithoutNumber.substring(2) + "else: " + ite.elsCond.toSimpleString()+"\n"
        str = str + indent + toIdeHierarchy(ite.elsCond, n+1)
        for(sub <- ite.elsSubs){
          str = str + indent + toIdeHierarchy(sub, n+1)
        }
      }
      case ce: CondExpRecord => {
        str = str + ce.toString()+"\n"
        str = str + indent + toIdeHierarchy(ce.thnExp, n+1)
        str = str + indent + toIdeHierarchy(ce.elsExp, n+1)
        return str
      }
      case gb: GlobalBranchRecord => {
        str = str + "branch1:\n"
        for(sub <- gb.thnSubs){
          str = str + indent + toIdeHierarchy(sub, n+1)
        }

        str = str + n+indentWithoutNumber.substring(2) + "branch2:\n"
        for(sub <- gb.elsSubs){
          str = str + indent + toIdeHierarchy(sub, n+1)
        }
      }
      case mc: MethodCallRecord => {
        str = str + mc.toString()+"\n"
        str = str + indent + "pre: " + toIdeHierarchy(mc.precondition, n+1)
        str = str + indent + "post: " + toIdeHierarchy(mc.postcondition, n+1)
        for(p <- mc.parameters) {
          str = str + indent + "param: " + toIdeHierarchy(p, n+1)
        }
      }
      case _ => {
        str = str + s.toString() + "\n"
        for (sub <- s.subs) {
          str = str + indent + toIdeHierarchy(sub, n + 1)
        }
      }
    }
    return str
  }
}

class TypeTreeRenderer extends Renderer[String] {
  def render(memberList: List[SymbLog]): String = {
    var res = ""
    for (m <- memberList){
      res = res + renderMember(m) + "\n"
    }
    res
  }

  def renderMember(member: SymbLog): String = {
    toTypeTree(member.main, 1)
  }

  def toTypeTree(s: SymbolicRecord, n: Int): String = {
    var indent = ""
    for(i <- 1 to n) {
      indent = "  " + indent
    }
    var str = ""

    s match {
      case ite: IfThenElseRecord => {
        str = str + "if\n"
        str = str + indent + toTypeTree(ite.thnCond, n+1)
        for(sub <- ite.thnSubs){
          str = str + indent + toTypeTree(sub, n+1)
        }

        str = str + indent.substring(2) + "else\n"
        str = str + indent + toTypeTree(ite.elsCond, n+1)
        for(sub <- ite.elsSubs){
          str = str + indent + toTypeTree(sub, n+1)
        }
      }
      case ce: CondExpRecord => {
        str = str + "CondExp\n"
        str = str + indent + toTypeTree(ce.thnExp, n+1)
        str = str + indent + toTypeTree(ce.elsExp, n+1)
      }
      case gb: GlobalBranchRecord => {
        str = str + gb.toTypeString + "\n"
        for(sub <- gb.thnSubs){
          str = str + indent + toTypeTree(sub, n+1)
        }
        for(sub <- gb.elsSubs){
          str = str + indent + toTypeTree(sub, n+1)
        }
      }
      case mc: MethodCallRecord => {
        str = str + "MethodCall\n"
        str = str + indent + "precondition\n"
        str = str + indent + "  " + toTypeTree(mc.precondition, n+2)
        str = str + indent + "postcondition\n"
        str = str + indent + "  " + toTypeTree(mc.postcondition, n+2)
        for(p <- mc.parameters) {
          str = str + indent + "parameter\n"
          str = str + indent + "  " + toTypeTree(p, n+2)
        }
      }
      case _ => {
        str = s.toTypeString()+"\n"
        for(sub <- s.subs){
          str = str + indent + toTypeTree(sub, n+1)
        }
      }
    }
    str
  }
}


//=========== Records =========

sealed trait SymbolicRecord {
  val value: silver.ast.Node
  val state: AnyRef
  val context: DefaultContext
  var subs = List[SymbolicRecord]()

  def toTypeString(): String {}

  override def toString(): String = {
    toTypeString() + " " + toSimpleString()
  }

  def toSimpleString(): String = {
    if(value != null)  value.toString()
    else "null"
}

  def dotNode(): String = {
    this.hashCode().toString()
  }

  def dotLabel(): String = {
    "\""+this.toString()+"\""
  }
}

trait MemberRecord extends SymbolicRecord
trait MultiChildRecord extends SymbolicRecord
trait MultiChildOrderedRecord extends MultiChildRecord
trait MultiChildUnorderedRecord extends MultiChildRecord
trait SequentialRecord extends SymbolicRecord

class MethodRecord(v: silver.ast.Method, s: AnyRef, c: DefaultContext) extends MemberRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString(): String = { "method" }

  override def toSimpleString():String = {
    if(value != null) value.name
    else "null"
  }
}

class PredicateRecord(v: silver.ast.Predicate, s: AnyRef, c: DefaultContext) extends MemberRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString(): String = { "predicate" }

  override def toSimpleString():String = {
    if(value != null) value.name
    else "null"
  }
}

class FunctionRecord(v: silver.ast.Function, s: AnyRef, c: DefaultContext) extends MemberRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString(): String = { "function" }

  override def toSimpleString():String = {
    if(value != null) value.name
    else "null"
  }
}

class ExecuteRecord(v: silver.ast.Stmt, s: AnyRef, c: DefaultContext) extends SequentialRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString(): String = { "execute" }

  override def toSimpleString():String = {
    if(value != null) {
      value.pos.toString() + " " + value.toString()
    }
    else "null"
  }
}

class EvaluateRecord(v: silver.ast.Exp, s: AnyRef, c: DefaultContext) extends SequentialRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString(): String = { "evaluate" }
  override def toSimpleString():String = {
    if(value != null) {
      value.pos.toString() + " " + value.toString()
    }
    else "null"
  }
}

class ProduceRecord(v: silver.ast.Exp, s: AnyRef, c: DefaultContext) extends SequentialRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString(): String = { "produce" }
  override def toSimpleString():String = {
    if(value != null) {
      value.pos.toString() + " " + value.toString()
    }
    else "null"
  }
}

class ConsumeRecord(v: silver.ast.Exp, s: AnyRef, c: DefaultContext)
    extends SequentialRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString(): String = { "consume" }
  override def toSimpleString():String = {
    if(value != null) {
      value.pos.toString() + " " + value.toString()
    }
    else "null"
  }
}

class IfThenElseRecord(v: silver.ast.Exp, s: AnyRef, c: DefaultContext)
    extends MultiChildUnorderedRecord {
  val value = v //meaningless since there is no directly usable if-then-else structure in the AST
  val state = s
  val context = c
  def toTypeString(): String = { "IfThenElse" }

  var thnCond:SymbolicRecord = new CommentRecord("Unreachable", null, null)
  var elsCond:SymbolicRecord = new CommentRecord("Unreachable", null, null)
  var thnSubs = List[SymbolicRecord](new CommentRecord("Unreachable", null, null))
  var elsSubs = List[SymbolicRecord](new CommentRecord("Unreachable", null, null))

  override def toString(): String = {
    "if "+thnCond.toSimpleString()
  }

  override def toSimpleString(): String = {
    "if "+thnCond.toSimpleString()
  }

  def finish_thnCond(): Unit = {
    if(!subs.isEmpty)
      thnCond = subs(0)
    subs = List[SymbolicRecord]()
  }

  def finish_elsCond(): Unit = {
    if(!subs.isEmpty)
      elsCond = subs(0)
    subs = List[SymbolicRecord]()
  }

  def finish_thnSubs(): Unit = {
    if(!subs.isEmpty)
      thnSubs = subs
    subs = List[SymbolicRecord]()
  }

  def finish_elsSubs(): Unit = {
    if(!subs.isEmpty)
      elsSubs = subs
    subs = List[SymbolicRecord]()
  }
}

class CondExpRecord(v: silver.ast.Exp, s: AnyRef, c: DefaultContext, env: String)
    extends MultiChildOrderedRecord {
  val value = v
  val state = s
  val context = c
  val environment = env
  def toTypeString(): String = { "CondExp" }

  var cond:SymbolicRecord   = new CommentRecord("<missing condition>", null, null)
  // thn/els Exp is Unreachable by default. If this is not the case, it will be overwritten
  var thnExp:SymbolicRecord = new CommentRecord("Unreachable", null, null)
  var elsExp:SymbolicRecord = new CommentRecord("Unreachable", null, null)

  override def toString(): String = {
    if(value != null)
      environment + " "+value.toString()
    else
      "CondExp <null>"
  }

  override def toSimpleString(): String = {
    if(value != null)
      value.toString()
    else
      "CondExp <Null>"
  }

  def finish_cond(): Unit ={
    if(!subs.isEmpty)
      cond = subs(0)
    subs = List[SymbolicRecord]()
  }

  def finish_thnExp(): Unit ={
    if(!subs.isEmpty)
      thnExp = subs(0)
    subs = List[SymbolicRecord]()
  }

  def finish_elsExp(): Unit ={
    if(!subs.isEmpty)
      elsExp = subs(0)
    subs = List[SymbolicRecord]()
  }
}

class GlobalBranchRecord(v: silver.ast.Exp, s: AnyRef, c: DefaultContext, env: String)
    extends MultiChildUnorderedRecord {
  val value = v
  val state = s
  val context = c
  val environment = env
  def toTypeString(): String = { "GlobalBranch" }

  var cond:SymbolicRecord = new CommentRecord("<missing condition>", null, null)
  var thnSubs = List[SymbolicRecord](new CommentRecord("Unreachable", null, null))
  var elsSubs = List[SymbolicRecord](new CommentRecord("Unreachable", null, null))

  override def toSimpleString(): String = {
    if(value != null)
      value.toString()
    else
      "GlobalBranch<Null>"
  }

  override def toString(): String = {
    environment + " " + toSimpleString()
  }

  def finish_cond(): Unit ={
    if(!subs.isEmpty)
      cond = subs(0)
    subs = List[SymbolicRecord]()
  }

  def finish_thnSubs(): Unit ={
    if(!subs.isEmpty)
      thnSubs = subs
    subs = List[SymbolicRecord]()
  }

  def finish_elsSubs(): Unit ={
    if(!subs.isEmpty)
      elsSubs = subs
    subs = List[SymbolicRecord]()
  }
}

class CommentRecord(str: String, s: AnyRef, c: DefaultContext) extends SequentialRecord {
  val value = null
  val state = s
  val context = c
  def toTypeString():String = { "Comment" }

  val comment = str

  override def toSimpleString(): String = {
    if(comment != null) comment
    else "null"
  }

  override def toString(): String = {
    "comment: " + toSimpleString()
  }

  override def dotLabel(): String = {
    "\""+comment+"\""
  }
}

class MethodCallRecord(v: silver.ast.MethodCall, s: AnyRef, c: DefaultContext)
    extends MultiChildOrderedRecord {
  val value = v
  val state = s
  val context = c
  def toTypeString():String = { "MethodCall" }

  var parameters = List[SymbolicRecord]()
  var precondition: SymbolicRecord = new ConsumeRecord(null,null, null)
  var postcondition:SymbolicRecord = new ProduceRecord(null,null, null)

  override def toString(): String = {
    if(value != null)
      "execute: " + value.toString()
    else
      "execute: MethodCall <null>"
  }

  override def toSimpleString(): String = {
    if(v != null) v.toString()
    else "MethodCall <null>"
  }

  def finish_parameters(): Unit = {
    parameters = subs // No check for emptyness. empty subs = no parameters, which is perfectly fine.
    subs = List[SymbolicRecord]()
  }

  def finish_precondition(): Unit = {
    if(!subs.isEmpty)
      precondition = subs(0)
    subs = List[SymbolicRecord]()
  }

def finish_postcondition(): Unit = {
    if(!subs.isEmpty)
      postcondition = subs(0)
    subs = List[SymbolicRecord]()
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
 *    At the point in the code where you want to add the comment, call
 *    //SymbExLogger.currentLog().insert(new CommentRecord(my_info, σ, c)
 *    //SymbExLogger.currentLog().collapse()
 *    σ is the current state (AnyRef, but should be of type State[_,_,_] usually), my_info
 *    is a string that contains the information you want to store, c is the current
 *    context. If you want to store more information than just a string, consider (2).
 *
 * Use of custom Records (2):
 *    For already existing examples, have a look at CondExpRecord (local Branching) or IfThenElseRecord
 *    (recording of If-Then-Else-structures).
 *    Assume that you want to have a custom record for  (non-short-circuiting) evaluations of
 *    silver.ast.And, since you want to differ between the evaluation of the lefthandside
 *    and the righthandside (not possible with default recording).
 *    Your custom record could look similar to this:
 *
 *    class AndRecord(v: silver.ast.And, s: AnyRef, c: DefaultContext) extends SymbolicRecord {
 *      val value = v    // Due to inheritance. This is what gets recorded by default.
 *      val state = s    // "
 *      val context = c  // "
 *
 *      lhs: SymbolicRecord = new CommentRecord("null", null, null)
 *      rhs: SymbolicRecord = new CommentRecord("null", null, null)
 *      // lhs & rhs are what you're interested in. The first initialization should be overwritten anyway,
 *      // initialization with a CommentRecord just ensures that the logger won't crash due
 *      // to a Null Exception (ideally). Can also be used if you're unsure if a certain structure is
 *      // evaluated at all; e.g., the righthandside might not be evaluated because the lefthandside
 *      // is already false (see IfThenElseRecord: paths might be unreachable, so the default is
 *      // a CommentRecord("Unreachable", null, null) which is not overwritten due to unreachability.
 *
 *      def finish_lhs(): Unit = {
 *        if(!subs.isEmpty) //so you don't overwrite your default CommentRecord if subs is empty
 *          lhs = subs(0)
 *        subs = List[SymbolicRecord]()
 *      }
 *
 *      def finish_rhs(): Unit = {
 *        if(!subs.isEmpty)
 *          rhs = subs(0)
 *        subs = List[SymbolicRecord]()
 *      }
 *
 *      // finish_FIELD() is the method that overwrites the given field with what is currently in 'subs'.
 *      // For usage example, see below.
 *    }
 *
 *    Usage of your new custom record 'AndRecord':
 *    This is the code in the DefaultEvaluator (after unrolling of evalBinOp):
 *
 *    case ast.And(e0, e1) if config.disableShortCircuitingEvaluations() =>
 *      eval(σ, e0, pve, c)((t0, c1) =>
 *        eval(σ, e1, pve, c1)((t1, c2) =>
 *          Q(And(t0, t1), c2)))
 *
 *    Use of the new record:
 *
 *    case and @ ast.And(e0, e1) if config.disableShortCircuitingEvaluations() =>
 *      andRec = new AndRecord(and, σ, c)
 *      SymbExLogger.currentLog().insert(andRec)
 *      eval(σ, e0, pve, c)((t0, c1) => {
 *        andRec.finish_lhs()
 *        eval(σ, e1, pve, c1)((t1, c2) => {
 *          andRec.finish_rhs()
 *          SymbExLogger.currentLog().collapse()
 *          Q(And(t0, t1), c2)))}}
 *
 *    The record is now available for use; now its representation needs to be implemented,
 *    which is done Renderer-Classes. Implement a new case in all renderer for the new
 *    record.
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

    if(testIsExecuted) {
      val pw = new java.io.PrintWriter(new File(actualPath))
      try pw.write(SymbExLogger.toTypeTreeString()) finally pw.close()

      val expectedSource = scala.io.Source.fromFile(expectedPath)
      val expected = expectedSource.getLines()

      val actualSource = scala.io.Source.fromFile(actualPath)
      val actual = actualSource.getLines()

      var lineNumber = 0

      while(!testFailed && expected.hasNext && actual.hasNext) {
        if(!actual.next().equals(expected.next())) {
          testFailed = true
        }
        lineNumber = lineNumber + 1
      }
      if(actual.hasNext != expected.hasNext)
        testFailed = true

      if(testFailed) {
        errorMsg = errorMsg + "Unit Test failed, expected output "
        errorMsg = errorMsg + "does not match actual output. "
        errorMsg = errorMsg + "First occurrence at line " + lineNumber + ".\n"
        errorMsg = errorMsg + "Compared Files:\n"
        errorMsg = errorMsg + "expected: " + expectedPath.toString() + "\n"
        errorMsg = errorMsg + "actual:   " + actualPath.toString() + "\n"
      }
      actualSource.close()
      expectedSource.close()
    }
    if(testIsExecuted && testFailed){
      return Seq(new SymbExLogUnitTestError(errorMsg))
    }
    else {
      return Nil
    }
  }
}

case class SymbExLogUnitTestError(msg: String) extends AbstractError {
  def pos = NoPosition
  def fullId = "symbexlogunittest.error"
  def readableMessage = msg
}