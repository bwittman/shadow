/* Generated By:JJTree: Do not edit this line. ASTAssignmentOperator.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=true,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package shadow.parser.javacc;

public
@SuppressWarnings("all")
class ASTAssignmentOperator extends SimpleNode {
  public ASTAssignmentOperator(int id) {
    super(id);
  }

  public ASTAssignmentOperator(ShadowParser p, int id) {
    super(p, id);
  }


  /** Accept the visitor. **/
  public Object jjtAccept(ShadowParserVisitor visitor, Boolean secondVisit) throws ShadowException {
    return visitor.visit(this, secondVisit);
  }
  
  public enum AssignmentKind {
	  EQUAL("=", ""),
	  CAT("#", "concatenate"),
	  PLUS("+", "add"),
	  MINUS("-", "subtract"),
	  STAR("*", "multiply"),
	  SLASH("/", "divide"),
	  MOD("%", "modulus"),
	  AND("&", "bitAnd"),
	  OR("|", "bitOr"),
	  XOR("^", "bitXor"),
	  LEFT_SHIFT("<<", "bitShiftLeft"),
	  RIGHT_SHIFT(">>", "bitShiftRight"),
	  LEFT_ROTATE("<<<", "bitRotateLeft"),
	  RIGHT_ROTATE(">>>", "bitRotateLeft");	  
	  
	  private String operator;
	  private String method;
	  
	  AssignmentKind( String operator, String method )
	  {
		  this.operator = operator;
		  this.method = method;		  
	  }
	  
	  public String getOperator()
	  {
		  return operator;
	  }
	  
	  public String getMethod()
	  {
		  return method;
	  }
  }
  
  protected AssignmentKind assignmentType;
  
  public void setAssignmentType(AssignmentKind type) {
	this.assignmentType = type;  
  }
  
  public AssignmentKind getAssignmentType() {
	  return this.assignmentType;
  }
}
/* JavaCC - OriginalChecksum=92708bdf2f854aa06c093a0c5cea3295 (do not edit this line) */
