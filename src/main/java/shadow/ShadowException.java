package shadow;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;

import shadow.parse.Context;

public abstract class ShadowException extends Exception {
	private static final long serialVersionUID = 750991826899853128L;
	private static final String EOL = System.getProperty("line.separator", "\n");
	private final Context context;

	public ShadowException(String message) {
		super(message);		
		context = null;
	}
	
	public ShadowException(String message, Context context) {
		super(message);
		this.context = context;
	}
	
	/**
	 * Gets path where error happened.
	 * @return			path
	 */
	public Path getPath() {
		if( context != null )
			return context.getPath();
		return null;
	}
	
	/**
	 * Gets starting line where error happened.
	 * @return			line
	 */
	public int lineStart() {
		if( context != null )
			return context.lineStart();
		return -1;
	}
	
	/**
	 * Gets ending line where error happened.
	 * @return			line
	 */
	public int lineEnd() {
		if( context != null )
			return context.lineEnd();
		return -1;
	}
	
	/**
	 * Gets starting column where error happened.
	 * @return			column
	 */
	public int columnStart() {
		if( context != null )
			return context.columnStart();
		return -1;
	}
	
	/**
	 * Gets ending column where error happened.
	 * @return			column
	 */
	public int columnEnd() {
		if( context != null )
			return context.columnEnd();
		return -1;
	}
	
	public boolean isInside(ShadowException other)
	{
		if( context != null && other.context != null ) {			
			Path path1 = context.getPath();
			Path path2 = other.context.getPath();			
			if( path1 != null && path2 != null )			
				return path1.equals(path2) &&
					lineStart() >= other.lineStart() &&
					lineEnd() <= other.lineEnd() &&
					(lineStart() > other.lineStart() || columnStart() >= other.columnStart() ) &&
					(lineEnd() < other.lineEnd() || columnEnd() <= other.columnEnd() );
		}
		return false;			
	}
	
	/**
	 * Creates a formatted message for an error or warning, including file name and
	 * line and column numbers and possibly text from the file itself where the error is.
	 * File, line, and column values may be special defaults which will cause
	 * them to be ignored in the return message.
	 * @param kind				kind of error or warning		
	 * @param message			message explaining the error or warning
	 * @param context			context where problem is occurring
	 * @return					formatted message
	 */
	public static String makeMessage( ShadowExceptionFactory kind, String message, Context context ) {
		StringBuilder error = new StringBuilder();
		
		if( context != null ) {		
			if( context.getPath() != null )
				error.append("(" + context.getPath().getFileName().toString() + ")");
			
			if( context.lineStart() != -1 && context.columnStart() != -1 )
				error.append("[" + context.lineStart() + ":" + context.columnStart() + "] ");
			else
				error.append(" ");
			
			if( kind != null )
				error.append(kind.getName() + ": ");		
			
			error.append(message);
			
			/* If file is available, find problematic text and include it in the message. */	
			if( context.getPath() != null && context.lineStart() >= 0 && context.lineEnd() >= context.lineStart() &&
					context.columnStart() >= 0 && context.columnEnd() >= 0 ) {
				BufferedReader reader = null;
				try {
					reader = new BufferedReader(new FileReader(context.getPath().toFile()));
					String line = "";			
					for( int i = 1; i <= context.lineStart(); ++i )
						line = reader.readLine();
					error.append(EOL);
					error.append(line);
					if( context.lineStart() == context.lineEnd() ) {
						error.append(EOL);
						for( int i = 1; i <= context.columnEnd(); ++i )
							if( i >= context.columnStart() )
								error.append('^');
							else
								error.append(' ');
					}
				} 
				// Do nothing, can't add additional file data
				catch (FileNotFoundException e) {}
				catch (IOException e) {}
				finally {
				  if( reader != null )
					try { reader.close(); }
				  	catch (IOException e) {}
			  }
			}
		}
		else {			
			if( kind != null )
				error.append(kind.toString() + ": ");	
			error.append(message);
		}
		
		return error.toString();
	}
	
	public abstract ShadowExceptionFactory getError();
}
