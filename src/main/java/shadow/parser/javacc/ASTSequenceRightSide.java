/* Generated By:JJTree: Do not edit this line. ASTSequenceRightSide.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=true,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package shadow.parser.javacc;

public
@SuppressWarnings("all")
class ASTSequenceRightSide extends SequenceNode {
  public ASTSequenceRightSide(int id) {
    super(id);
  }

  public ASTSequenceRightSide(ShadowParser p, int id) {
    super(p, id);
  }


  /** Accept the visitor. **/
  public Object jjtAccept(ShadowParserVisitor visitor, Boolean data) throws ShadowException {
    return visitor.visit(this, data);
  }
}
/* JavaCC - OriginalChecksum=5a4c5cfc513429c8ad9c4093c34cfa12 (do not edit this line) */