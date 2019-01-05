package org.github.sirnewton01.jdbc.ng;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;

/**
 * JDBC-NG proxy provides proxies for getting and setting SQL query positional
 *  arguments and result sets using native Java methods.
 * 
 * The low-level JDBC has a number of drawbacks that the proxies are intended
 *  to resolve. It allows raw SQL query strings to be used without preparing the
 *  statements first. The queries could have been assembled using tainted data.
 *  For the positional arguments there is no way to set their values by name
 *  and Java type, which is error prone when changes are made to the statement.
 *  The result sets are generic and have no typed get methods, which can
 *  also be error prone.
 * 
 * Proxies use Java interfaces to discover the SQL file with the statement.
 *  Since the file is loaded statically there is no chance that it can be tainted
 *  with user data either from the application or the database. The default search
 *  location for the SQL file is the same Java package as the interface. It searches
 *  for a file with the same name as the interface with a &quot;.sql&quot; file
 *  extension. An optional {@link Statement} annotation can be applied to it for
 *  and alternate location.
 * 
 * The Java interface must have an {@link PreparedStatement#execute()},
 *  {@link PreparedStatement#executeQuery()} or {@link PreparedStatement#executeUpdate()}
 *  method, which executes the statement depending on the nature of the statement.
 * 
 * If there are positional arguments in the statement the Java interface can have
 *  setter methods with names and types for those arguments. The {@link Pos} annotation
 *  indicates the argument position for each setter method. The caller sets each
 *  argument in the natural Java way.
 * 
 * If the Java interface implements the executeQuery() method then it returns a
 *  result set interface that extends {@link JdbcNgResultSet}. The result set
 *  interface can be iterated using next() and closed using close(), analogous
 *  to the JDBC ResultSet. However, the interface can expose the named and typed
 *  columns of the statement using simple getter methods with names that match
 *  the name of the column in the statement. Note that reserved characters such as
 *  '.' are converted to '_'. The result set interface implements AutoCloseable
 *  so that it can be placed into a try-with-resources block preventing leaks.
 * 
 * It is possible for discrepancies in names, positions and types between the
 *  Java interfaces and the SQL statement in the file. Since these would normally
 *  only be found at runtime there is a {@link #validateInterface(java.sql.Connection, java.lang.Class) }
 *  method that can be used to detect problems earlier in the presence of a database
 *  connection that has all of the necessary schemas and tables to prepare the statement.
 *  This validate method can be used to iterate on all known JDBC NG interfaces
 *  in early startup routines or even in build automation scripts so that any
 *  discrepancies can be caught earlier in the release cycle.
 */
public class JdbcNgProxy {
    private static final Map<Class, String> PROCESSED_INTERFACES = Collections.synchronizedMap(new HashMap());
    
    public static <T> T generateProxy(Connection dbConn, final Class<T> aInterface) throws IOException, SQLException {
	PreparedStatement pstmt = loadPreparedStatement(aInterface, dbConn);
	
	return (T) Proxy.newProxyInstance(aInterface.getClassLoader(), new Class[] {aInterface}, new InvocationHandler() {
	    @Override
	    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Pos pos = method.getAnnotation(Pos.class);
		
		// Positional argument setter method
		if (pos != null) {
		    pstmt.setObject(pos.value(), args[0]);
		    return null;
		}
		
		// Execute query returns a result set
		if (method.getName().equals("executeQuery")) {
		    final ResultSet rs = pstmt.executeQuery();
		    
		    return Proxy.newProxyInstance(aInterface.getClassLoader(), new Class[] {method.getReturnType()}, new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			    if (method.getName().equals("next")) {
				return rs.next();
			    }
			    
			    if (method.getName().equals("close")) {
				rs.close();
				return null;
			    }
			    
			    String columnName = method.getName().substring(3);
			    columnName = columnName.substring(0, 1).toLowerCase() + columnName.substring(1);
			    
			    return rs.getObject(columnName);
			}
		    });
		}
		
		if (method.getName().equals("execute")) {
		    return pstmt.execute();
		}
		
		if (method.getName().equals("executeUpdate")) {
		    return pstmt.executeUpdate();
		}
		
		return null;
	    }
	});
    }

    private static PreparedStatement loadPreparedStatement(final Class<?> aInterface, Connection dbConn) throws SQLException, IOException {
	String stmtContents = PROCESSED_INTERFACES.get(aInterface);
	if (stmtContents == null) {
	    Statement stmtFile = aInterface.getAnnotation(Statement.class);
	    String resourceName;
	    
	    if (stmtFile != null) {
		resourceName = stmtFile.value();
	    } else {
		resourceName = aInterface.getSimpleName() + ".sql";
	    }
	    
	    if (!resourceName.contains("/")) {
		resourceName = aInterface.getPackage().getName().replace('.', '/') + "/" + resourceName;
	    }
	    
	    StringWriter writer = new StringWriter();
	    IOUtils.copy(aInterface.getClassLoader().getResourceAsStream(resourceName), writer, "UTF-8");
	    stmtContents = writer.toString();
	}
	final PreparedStatement pstmt = dbConn.prepareStatement(stmtContents);
	return pstmt;
    }

    public static void validateInterface(Connection conn, Class<?> aInterface) throws SQLException, IOException {
	PreparedStatement pstmt = loadPreparedStatement(aInterface, conn);
	
	// Validate that the prepared statement matches the interface so that we can
	//  fail early with a mismatch.
	
	int matchCount = 0;
	for (Method m: aInterface.getDeclaredMethods()) {
	    Pos pos = m.getAnnotation(Pos.class);
	    
	    if (pos == null) {
		continue;
	    }
	    
	    if (!m.getName().startsWith("set")) {
		   throw new IllegalArgumentException("Interface positional argument set method must begin with a prefix 'set'.");
	    }
	    
	    if (m.getParameterCount() != 1) {
		throw new IllegalArgumentException("Interface positional argument set method " + m.getName() + " must accept exactly one parameter.");
	    }
	    
	    if (m.getReturnType() != Void.TYPE) {
		throw new IllegalArgumentException("Interface positional argument set method " + m.getName() + " must not have a return type.");
	    }
	    
	    validateTypesEquivalent(pstmt.getParameterMetaData().getParameterType(pos.value()), m.getParameterTypes()[0]);
	    
	    matchCount++;
	}
	
	if (matchCount != pstmt.getParameterMetaData().getParameterCount()) {
	    throw new IllegalArgumentException("The number of positional arguments doesn't match the number of positional argument setters in the interface.");
	}
	
	// Sanity check on a result set class, if there is one
	try {
	    Method executeQuery = aInterface.getDeclaredMethod("executeQuery");
	    if (executeQuery.getReturnType() == Void.TYPE || executeQuery.getReturnType().isPrimitive()) {
		throw new IllegalArgumentException("Interface has an executeQuery method that doesn't return a result set object.");
	    }
	    
	    Class<?> resultSetClass = executeQuery.getReturnType();
	    if (!Arrays.asList(resultSetClass.getInterfaces()).contains(JdbcNgResultSet.class)) {
		throw new IllegalArgumentException("Result set class " + resultSetClass.getName() + " must implement JdbcNgResultSet.");
	    }
	    
	    for (Method m: resultSetClass.getDeclaredMethods()) {
		if (m.getName().startsWith("get")) {
		    String columnName = m.getName().substring(3);
		    columnName = columnName.substring(0, 1).toLowerCase() + columnName.substring(1);
		    
		    boolean found = false;
		    for (int i=1; i <= pstmt.getMetaData().getColumnCount(); i++) {
			if (pstmt.getMetaData().getColumnLabel(i).equals(columnName)) {
			    found = true;
			    
			    validateTypesEquivalent(pstmt.getMetaData().getColumnType(i), m.getReturnType());
			    
			    break;
			}
		    }
		    
		    if (!found) {
			throw new IllegalArgumentException("Result set class " + resultSetClass.getName() + " has a method for column " + columnName + " that doesn't exist.");
		    }
		}
	    }
	} catch (NoSuchMethodException ex) {
	    // It doesn't exist, no problem
	} catch (SecurityException ex) {
	    Logger.getLogger(JdbcNgProxy.class.getName()).log(Level.SEVERE, null, ex);
	}
	
	try {
	    Method executeUpdate = aInterface.getDeclaredMethod("executeUpdate");
	    if (executeUpdate.getReturnType() != Integer.TYPE) {
		throw new IllegalArgumentException("Interface has an executeUpdate method that doesn't return an int.");
	    }
	} catch (NoSuchMethodException ex) {
	    // No problem
	} catch (SecurityException ex) {
	    Logger.getLogger(JdbcNgProxy.class.getName()).log(Level.SEVERE, null, ex);
	}
    }

    private static void validateTypesEquivalent(int jdbcType, Class<?> javaType) {
	// TODO create a mapping of equivalent types and throw an exception if these two don't match
    }
}
