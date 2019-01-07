package com.github.sirnewton01.jdbc.ng;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Date;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestJdbcNgProxy {
    
    public TestJdbcNgProxy() {
    }
    
    private Connection conn;
    
    @BeforeAll
    public void setup() throws ClassNotFoundException, InstantiationException, IllegalAccessException, SQLException {
	String driver = "org.apache.derby.jdbc.EmbeddedDriver";
	Class.forName(driver).newInstance();
	
	conn = DriverManager.getConnection("jdbc:derby:memory:myInMemDB;create=true", new Properties());
    }
    
    private interface sanity1createtablestmt {
	public int executeUpdate();
    }
    
    private interface sanity1createrowstmt {
	@Pos(1) public void setField1(int i);
	@Pos(2) public void setField2(String s);
	@Pos(3) public void setField3(Date d);
	public int executeUpdate();
    }
    
    private interface sanity1getrowstmt {
	@Pos(1) public void setA(int a);
	public sanity1getrowrs executeQuery();
    }
    
    private interface sanity1getrowrs extends JdbcNgResultSet {
	public int getField1();
	public String getField2();
	public Date getField3();
    }
    
    @Test
    public void testSanity1() throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
	setup();
	
	sanity1createtablestmt s1t = JdbcNg.generateProxy(conn, sanity1createtablestmt.class);
	assertEquals(0, s1t.executeUpdate());
	
	sanity1createrowstmt s1c = JdbcNg.generateProxy(conn, sanity1createrowstmt.class);
	s1c.setField1(1);
	s1c.setField2("abc");
	s1c.setField3(new Date(94, 1, 23));
	assertEquals(1, s1c.executeUpdate());
	
	sanity1getrowstmt s1 = JdbcNg.generateProxy(conn, sanity1getrowstmt.class);
	s1.setA(1);
	try (sanity1getrowrs rs = s1.executeQuery()) {
	    while (rs.next()) {
		assertEquals(1, rs.getField1());
		assertEquals("abc", rs.getField2());
		assertEquals(new Date(94, 1, 23), rs.getField3());
	    }
	}
    }
}
