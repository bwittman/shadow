/* Generated By:JJTree: Do not edit this line. ASTClassOrInterfaceType.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=true,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package shadow.parser.javacc;

import shadow.typecheck.Type;

public
@SuppressWarnings("all")
class ASTClassOrInterfaceType extends SimpleNode {
  public ASTClassOrInterfaceType(int id) {
    super(id);
  }

  public ASTClassOrInterfaceType(ShadowParser p, int id) {
    super(p, id);
  }

	public void setImage(String image) {
		this.image = image;
		this.type = new Type(image);
	}

  /** Accept the visitor. **/
  public Object jjtAccept(ShadowParserVisitor visitor, Object data) throws ShadowException {
    return visitor.visit(this, data);
  }
}
/* JavaCC - OriginalChecksum=30f1aa6f9a646cb6d09daa9dbef3db5c (do not edit this line) */
