/* Generated By:JJTree: Do not edit this line. ASTVariableDeclaratorId.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=true,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package shadow.parser.javacc;

public
@SuppressWarnings("all")
class ASTVariableDeclaratorId extends ModifiedNode {
  public ASTVariableDeclaratorId(int id) {
    super(id);
  }

  public ASTVariableDeclaratorId(ShadowParser p, int id) {
    super(p, id);
  }


  /** Accept the visitor. **/
  public Object jjtAccept(ShadowParserVisitor visitor, Boolean secondVisit) throws ShadowException {
    return visitor.visit(this, secondVisit);
  }
}
/* JavaCC - OriginalChecksum=84ef8348f2ea7e59df590f2c12912bcb (do not edit this line) */
