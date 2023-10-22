package org.data.spark

class InsertQPS(driver: String, url: String, user: String, pwd: String) extends Serializable {
  @transient
  var connection: java.sql.Connection = _
  @transient
  var pstmt: java.sql.PreparedStatement = _
  var counter: Int = 0
  val batchLimit: Int = 10
  val insertQuery = new StringBuilder(""" insert into qps (`eq_date`,`eq_hour`,`eq_req`) 
                  values (?, ?, ?) on duplicate key update eq_req = eq_req + ? """)
 
  def open(): Boolean = {
    Class.forName(driver)
    connection = java.sql.DriverManager.getConnection(url, user, pwd)
    connection.setAutoCommit(false)
    println("insertQuery::: "+ insertQuery)
    pstmt = connection.prepareStatement(insertQuery.toString())
    true
  }

  def process(row: org.apache.spark.sql.Row): Unit = {
    if (counter >= batchLimit) {
      pstmt.executeBatch()
      pstmt.clearBatch()
      connection.commit()
      counter = 0
    }
    if(row.getAs[java.util.Date]("log_date") != null)
    {
      pstmt.setDate(1, new java.sql.Date(row.getAs[java.util.Date]("log_date").getTime))
      pstmt.setInt(2, row.getAs("log_hour"))
      pstmt.setLong(3, row.getAs("req"))
      pstmt.setLong(4, row.getAs("req"))
      
      pstmt.addBatch()
      
      counter = counter + 1
    }
  }

  def clearBatch(): Unit = {
      pstmt.executeBatch()
      pstmt.clearBatch()
      connection.commit()
      counter = 0
  }
  
  def close(): Unit = {
    if (counter > 0) {
      pstmt.executeBatch()
      pstmt.clearBatch()
    }
    
    connection.commit()
    connection.close
  }
}