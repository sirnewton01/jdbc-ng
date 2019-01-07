/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.github.sirnewton01.jdbc.ng;

import java.sql.SQLException;


public interface JdbcNgResultSet extends AutoCloseable {
    public boolean next();
    public void close() throws SQLException;
}
