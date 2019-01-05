# JDBC Next Generation

ORM's (Object-Relational Mapping) tools, such as Hibernate and EclipseLink, currently
dominate over using plain JDBC. Support for multiple database vendors and the ability to
model database elements as Java classes make it an attractive option.

However, there are certain drawbacks. It encourages the creation of Java types that mirror
database tables that slowly accrete all of the data from the table. Meanwhile, in certain
scenarios you may only need a subset of the data, which can impact performance for all of the
scenarios. Since much of the SQL is hidden from the Java developer concerns, such as
performance can be difficult to mitigate.

If your project is tied to a particular database vendor then switching back to JDBC is an
option. Meanwhile, it has remained largely stale for the last decade. Problems such as SQL
injection are left to the developer making it very easy to shoot yourself in the foot.
Programming against large statements with many positional arguments or result set columns
is menial, but also error prone. Here is an illustrative example:

``` java

String SQL = "SELECT hotelName, hotelAddress, patronName, ..." +
		" CASE " + 
		" WHEN hotelName LIKE '%Motel%' THEN 'Motel'" +
		" ELSE 'Hotel'" +
		" END AS lodgingType" +
		"FROM hotels" +
		"LEFT JOIN patrons ON ..." +
		"WHERE invoiceid = ? AND paymentcode = " + request.getPaymentCode() " +
		" AND ...";

PreparedStatement stmt = conn.prepareStatement(SQL);
int colNum = 1;
stmt.setObject(colNum++, request.getInvoiceId());
stmt.setObject(colNum++, request.get...);
...

try (ResultSet rs = stmt.executeQuery()) {
	while (rs.next()) {
		hotelName = rs.getString(1);
		invoiceCode = rs.getInt(2);
		...
		handleHotelInvoice(hotelName, invoiceCode, ...);
	}
}
```

The SQL query is assembled in code and can be very easily tainted with user data, such as
from the request. Java doesn't have multi-line string literals, which makes it very hard
to format the SQL to make it readable. None of the major text editor or IDE's support any
kind of syntax highlighting or content-assist on the SQL inside a Java string. The query
really belongs inside a separate SQL file and this should be enforced by the tool.

Notice how the positional arguments are either hard-coded integers or use an incrementing
variable. When the query changes and adds another positional argument in the middle, it can
be error prone to insert the setObject() call in the correct place or update all of the magic
integers. There's really no other way with JDBC to handle it. It is up to the developer and
testers to verify that changes to the query work with this code in a variety of cases.

The types of the positional arguments are not checked by the compiler. Mistakes are discovered
only at runtime, such as an integer that should have been a string.

When getting the values from the result set there are similar position-based getters that either
return Objects that must be casted or the code must specify the correct getter with the correct
type. There is no checking for the types except at runtime. And the passing of position integers
or column label strings is more difficult to read.

## JDBC NG Example

Here is an example similar to the above but using JDBC-NG.

``` java
public interface HotelInvoiceStmt {
	@Pos(1) public void setInvoiceId(int id);
	@Pos(2) public void set...;

	public HotelInvoiceRs executeQuery();
}

public interface HotelInvoiceRs implements JdbcNgResultSet {
	public String getHotelName();
	public int InvoiceCode();
	...
}

...

HotelInvoiceStmt stmt = JdbcNgProxy.generateStatement(conn, HotelInvoiceStmt.class);
try (HotelInvoiceRs rs = stmt.executeQuery()) {
	while (rs.next()) {
		handleHotelInvoice(rs);
	}
}
```

The SQL statement is the same as above, but is not in a separate file called HotelInvoiceStmt.sql
in the same Java package. It's easier to read and edit with tools capable of authoring SQL along
with other capabilities such as syntax highlighting and content-assist, none of which are
possible in most Java editors. Also, there's no possibility of tainting the query with user
data, which can cause SQL injection.

The two interfaces provide typical Java get and set methods for the positional arguments and
result set columns. They are typed correctly and the compiler will identify any Java code that
attempts to use the wrong type. The names make Java interactions much clearer and less error
prone. Note that you don't need the result set interface for statements that do updates. In that
case the statement interface can have an "int executeUpdate()" method, which returns the number
of affected rows in the same way as the identially named method on the PreparedStatement class.

In some ways this solution provides similar benefits to ORM's. Instead of reflecting over
positional arguments using indexes or result sets there are native Java methods and types for
these, which makes the Java code easier to read and write. SQL code is never constructed using
tained data. There is no need for code generators.

On the surface this appears to be yet another ORM. There are important differences. Current
ORM's encourage the re-use of Java classes for different purposes. This pattern encourages the
development of a different interface for each scenario even though the same tables could be
involved in multiple scenarios. ORM's generally define a single Java interface for each table
while here the interfaces reflect the statement and result sets, which can span multiple tables.

The result is that each query is written by developers and tailored for each scenario. The Java
interfaces help to make it more natural to interact with them. Over time, the queries and code
for each scenario can evolve independently of other code making use of the database to optimize
the queries for each scenario. Database evolutions can also be managed more independently from
the Java code since only the SQL files would need to be adjusted as long as they maintain their
inputs and outputs. ORM's by contrast generally need to map 1-1 to table structures. The
integrity between SQL statements and the Java interfaces is a concern that is covered in the
next section.

## Integrity validation

Since the developers are authoring both the SQL and the Java interfaces there is a certain
duplication of the positional arguments and result set column information between the two.
The positional arguments can easily change their position or type. The result set columns
can also change name or type. Since the duplication spans languages neither system can detect
discrepancies out of the box.

Ideally, validation problems would be detected immediately at build or compile time by a
developer working in this area so that they can fix the problem. However, a SQL query's types
are only available after being compiled against a live database with the current schema. This
is not generally available at Java compile time. A next best place to make the validation
checks is when the application starts up where both the database and the latest SQL and Java
code is present at the same time. This is slightly better than validating at runtime since it
happens earlier and doesn't rely on the user to exercise the workflow to discover the validation
error.

JDBC-NG provides a validation method that you can call from your application's startup routine
once the database is available to validate all of your Java interfaces. Here is an example.

``` java

public void startupRoutine() {
...
Connection connection = ... /* Establish a database connection */
JdbcNgProxy.validateInterface(connection, foo.bar.baz.HotelInvoiceStmt.class);
JdbcNgProxy.validateInterface(connection, ...);
...

}
```

The validateInterface method will find and prepare the SQL statement with the database so that
it can compare the types of your interface methods with the types of the positional arguments
and result set columns. It does other sanity checks as well.
