package shadow.typecheck.type;


public class ErrorType extends ClassType
{
	public ErrorType(String typeName, int modifiers, ClassInterfaceBaseType outer ) {
		this( typeName, modifiers, outer, Type.ERROR );
	}
	
	public ErrorType(String typeName, int modifiers, ClassInterfaceBaseType outer, ClassType extendType ) {
		super( typeName, modifiers, outer, Kind.ERROR );
		setExtendType( extendType );
	}
}
