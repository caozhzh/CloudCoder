// CloudCoder - a web-based pedagogical programming environment
// Copyright (C) 2011, Jaime Spacco <jspacco@knox.edu>
// Copyright (C) 2011, David H. Hovemeyer <dhovemey@ycp.edu>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package org.cloudcoder.app.server.persist;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.cloudcoder.app.shared.model.Change;
import org.cloudcoder.app.shared.model.ChangeType;
import org.cloudcoder.app.shared.model.ConfigurationSetting;
import org.cloudcoder.app.shared.model.ConfigurationSettingName;
import org.cloudcoder.app.shared.model.Course;
import org.cloudcoder.app.shared.model.Event;
import org.cloudcoder.app.shared.model.Problem;
import org.cloudcoder.app.shared.model.Term;
import org.cloudcoder.app.shared.model.TestCase;
import org.cloudcoder.app.shared.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of IDatabase using JDBC.
 * 
 * @author David Hovemeyer
 */
public class JDBCDatabase implements IDatabase {
    private static final Logger logger=LoggerFactory.getLogger(JDBCDatabase.class);
	private static final String JDBC_URL = "jdbc:mysql://localhost" + /*":8889" +*/ "/cloudcoder?user=root&password=root";
	
	static {
		try {
			Class.forName("com.mysql.jdbc.Driver");
		} catch (Exception e) {
			throw new IllegalStateException("Could not load mysql jdbc driver", e);
		}
	}
	
	private static class InUseConnection {
		Connection conn;
		int refCount;
	}
	
	/*
	 * Need to consider how to do connection management better.
	 * For now, just use something simple that works.
	 */

	private ThreadLocal<InUseConnection> threadLocalConnection = new ThreadLocal<InUseConnection>();
	
	private Connection getConnection() throws SQLException {
		InUseConnection c = threadLocalConnection.get();
		if (c == null) {
			c = new InUseConnection();
			c.conn = DriverManager.getConnection(JDBC_URL);
			c.refCount = 0;
			threadLocalConnection.set(c);
		}
		c.refCount++;
		return c.conn;
	}
	
	private void releaseConnection() throws SQLException {
		InUseConnection c = threadLocalConnection.get();
		c.refCount--;
		if (c.refCount == 0) {
			c.conn.close();
			threadLocalConnection.set(null);
		}
	}
	
	@Override
	public ConfigurationSetting getConfigurationSetting(final ConfigurationSettingName name) {
		return databaseRun(new AbstractDatabaseRunnable<ConfigurationSetting>() {
			@Override
			public ConfigurationSetting run(Connection conn)
					throws SQLException {
				PreparedStatement stmt = prepareStatement(conn, "select s.* from configuration_settings as s where s.name = ?");
				stmt.setString(1, name.toString());
				ResultSet resultSet = executeQuery(stmt);
				if (!resultSet.next()) {
					return null;
				}
				ConfigurationSetting configurationSetting = new ConfigurationSetting();
				load(configurationSetting, resultSet, 1);
				return configurationSetting;
			}
			@Override
			public String getDescription() {
				return "retrieving configuration setting";
			}
		});
	}
	
	@Override
	public User authenticateUser(final String userName, final String password) {
		return databaseRun(new AbstractDatabaseRunnable<User>() {
			@Override
			public User run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(conn, "select * from users where username = ?");
				stmt.setString(1, userName);
				
				ResultSet resultSet = executeQuery(stmt);
				if (!resultSet.next()) {
					return null;
				}
				
				User user = new User();
				load(user, resultSet, 1);
				
				// Check password
				String encryptedPassword = HashPassword.computeHash(password, user.getSalt());
				
				logger.debug("Password check: " + encryptedPassword + ", " + user.getPasswordMD5());
				
				if (!encryptedPassword.equals(user.getPasswordMD5())) {
					// Password does not match
					return null;
				}
				
				// Authenticated!
				return user;
			}

			@Override
			public String getDescription() {
				return "retrieving user";
			}
		});
	}
	
	@Override
	public Problem getProblem(final User user, final int problemId) {
		return databaseRun(new AbstractDatabaseRunnable<Problem>() {
			@Override
			public Problem run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select problems.* from problems, courses, course_registrations " +
						" where problems.problem_id = ? " +
						"   and courses.id = problems.course_id " +
						"   and course_registrations.course_id = courses.id " +
						"   and course_registrations.user_id = ?"
				);
				stmt.setInt(1, problemId);
				stmt.setInt(2, user.getId());
				
				ResultSet resultSet = executeQuery(stmt);
				
				if (!resultSet.next()) {
					// no such problem, or user is not authorized to see this problem
					return null;
				}
				
				Problem problem = new Problem();
				load(problem, resultSet, 1);
				return problem;
			}
			
			@Override
			public String getDescription() {
				return "retrieving problem";
			}
		});
	}
	
	@Override
	public Change getMostRecentChange(final User user, final int problemId) {
		return databaseRun(new AbstractDatabaseRunnable<Change>() {
			@Override
			public Change run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select c.* from changes as c, events as e " +
						" where c.event_id = e.id " +
						"   and e.id = (select max(ee.id) from changes as cc, events as ee " +
						"                where cc.event_id = ee.id " +
						"                  and ee.problem_id = ? " +
						"                  and ee.user_id = ?)"
				);
				stmt.setInt(1, problemId);
				stmt.setInt(2, user.getId());
				
				ResultSet resultSet = executeQuery(stmt);
				if (!resultSet.next()) {
					return null;
				}
				
				Change change = new Change();
				load(change, resultSet, 1);
				return change;
			}
			public String getDescription() {
				return "retrieving latest code change";
			}
		});
	}
	
	@Override
	public Change getMostRecentFullTextChange(final User user, final int problemId) {
		return databaseRun(new AbstractDatabaseRunnable<Change>() {
			@Override
			public Change run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select c.* from changes as c, events as e " +
						" where c.event_id = e.id " +
						"   and e.id = (select max(ee.id) from changes as cc, events as ee " +
						"                where cc.event_id = ee.id " +
						"                  and ee.problem_id = ? " +
						"                  and ee.user_id = ? " +
						"                  and cc.type = ?)"
				);
				stmt.setInt(1, problemId);
				stmt.setInt(2, user.getId());
				stmt.setInt(3, ChangeType.FULL_TEXT.ordinal());

				ResultSet resultSet = executeQuery(stmt);
				if (!resultSet.next()) {
					return null;
				}
				Change change = new Change();
				load(change, resultSet, 1);
				return change;
			}
			@Override
			public String getDescription() {
				return " retrieving most recent full text change";
			}
		});
	}
	
	@Override
	public List<Change> getAllChangesNewerThan(final User user, final int problemId, final int baseRev) {
		return databaseRun(new AbstractDatabaseRunnable<List<Change>>() {
			@Override
			public List<Change> run(Connection conn) throws SQLException {
				List<Change> result = new ArrayList<Change>();
				
				PreparedStatement stmt = prepareStatement(
						conn,
						"select c.* from changes as c, events as e " +
						" where c.event_id = e.id " +
						"   and e.id > ? " +
						"   and e.user_id = ? " +
						"   and e.problem_id = ? " +
						" order by e.id asc"
				);
				stmt.setInt(1, baseRev);
				stmt.setInt(2, user.getId());
				stmt.setInt(3, problemId);
				
				ResultSet resultSet = executeQuery(stmt);
				while (resultSet.next()) {
					Change change = new Change();
					load(change, resultSet, 1);
					result.add(change);
				}
				
				return result;
			}
			@Override
			public String getDescription() {
				return " retrieving most recent text changes";
			}
		});
	}
	
	@Override
	public List<? extends Object[]> getCoursesForUser(final User user) {
		return databaseRun(new AbstractDatabaseRunnable<List<? extends Object[]>>() {
			@Override
			public List<? extends Object[]> run(Connection conn) throws SQLException {
				List<Object[]> result = new ArrayList<Object[]>();

				PreparedStatement stmt = prepareStatement(
						conn,
						"select c.*, t.* from courses as c, terms as t, course_registrations as r " +
						" where c.id = r.course_id " + 
						"   and c.term_id = t.id " +
						"   and r.user_id = ? " +
						" order by c.year desc, t.seq desc"
				);
				stmt.setInt(1, user.getId());
				
				ResultSet resultSet = executeQuery(stmt);
				
				while (resultSet.next()) {
					Course course = new Course();
					load(course, resultSet, 1);
					Term term = new Term();
					load(term, resultSet, Course.NUM_FIELDS + 1);
					result.add(new Object[]{course, term});
				}
				
				return result;
			}
			@Override
			public String getDescription() {
				return " retrieving courses for user";
			}
		});
	}
	
	@Override
	public List<Problem> getProblemsInCourse(final User user, final Course course) {
		return databaseRun(new AbstractDatabaseRunnable<List<Problem>>() {
			@Override
			public List<Problem> run(Connection conn) throws SQLException {
				//
				// Note that we have to join on course registrations to ensure
				// that we return courses that the user is actually registered for.
				//
				PreparedStatement stmt = prepareStatement(
						conn,
						"select p.* from problems as p, courses as c, course_registrations as r " +
						" where p.course_id = c.id " +
						"   and r.course_id = c.id " +
						"   and r.user_id = ? " +
						"   and c.id = ?"
				);
				stmt.setInt(1, user.getId());
				stmt.setInt(2, course.getId());
				
				ResultSet resultSet = executeQuery(stmt);
				
				List<Problem> resultList = new ArrayList<Problem>();
				while (resultSet.next()) {
					Problem problem = new Problem();
					load(problem, resultSet, 1);
					resultList.add(problem);
				}
				
				return resultList;
			}
			@Override
			public String getDescription() {
				return "retrieving problems for course";
			}
		});
	}
	
	@Override
	public void storeChanges(final Change[] changeList) {
		databaseRun(new AbstractDatabaseRunnable<Boolean>() {
			@Override
			public Boolean run(Connection conn) throws SQLException {
				// Store Events
				PreparedStatement insertEvent = prepareStatement(
						conn,
						"insert into events values (NULL, ?, ?, ?, ?)", 
						Statement.RETURN_GENERATED_KEYS
				);
				for (Change change : changeList) {
					storeNoId(change.getEvent(), insertEvent, 1);
					insertEvent.addBatch();
				}
				insertEvent.executeBatch();
				
				// Get the generated ids of the newly inserted Events
				ResultSet genKeys = super.getGeneratedKeys(insertEvent);
				int count = 0;
				while (genKeys.next()) {
					int id = genKeys.getInt(1);
					changeList[count].getEvent().setId(id);
					changeList[count].setEventId(id);
					count++;
				}
				if (count != changeList.length) {
					throw new SQLException("Did not get all generated keys for inserted events");
				}
				
				// Store Changes
				PreparedStatement insertChange = prepareStatement(
						conn,
						"insert into changes values (NULL, ?, ?, ?, ?, ?, ?, ?)"
				);
				for (Change change : changeList) {
					storeNoId(change, insertChange, 1);
					insertChange.addBatch();
				}
				insertChange.executeBatch();
				
				return true;
			}
			@Override
			public String getDescription() {
				return "storing text changes";
			}
		});
	}
	
	@Override
	public List<TestCase> getTestCasesForProblem(final int problemId) {
		return databaseRun(new AbstractDatabaseRunnable<List<TestCase>>() {
			@Override
			public List<TestCase> run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select * from test_cases where problem_id = ?");
				stmt.setInt(1, problemId);
				
				List<TestCase> result = new ArrayList<TestCase>();
				
				ResultSet resultSet = executeQuery(stmt);
				while (resultSet.next()) {
					TestCase testCase = new TestCase();
					load(testCase, resultSet, 1);
					result.add(testCase);
				}
				return result;
			}
			@Override
			public String getDescription() {
				return "getting test cases for problem";
			}
		});
	}

	private<E> E databaseRun(DatabaseRunnable<E> databaseRunnable) {
		try {
			Connection conn = null;
			boolean committed = false;
			try {
				conn = getConnection();
				conn.setAutoCommit(false);
				// FIXME: should retry if deadlock is detected
				E result = databaseRunnable.run(conn);
				conn.commit();
				committed = true;
				return result;
			} finally {
				if (conn != null) {
					if (!committed) {
						conn.rollback();
					}
					databaseRunnable.cleanup();
					conn.setAutoCommit(true);
					releaseConnection();
				}
			}
		} catch (SQLException e) {
			throw new PersistenceException("SQLException", e);
		}
	}

	private void load(ConfigurationSetting configurationSetting, ResultSet resultSet, int index) throws SQLException {
		configurationSetting.setName(resultSet.getString(index++));
		configurationSetting.setValue(resultSet.getString(index++));
	}

	private void load(User user, ResultSet resultSet, int index) throws SQLException {
		user.setId(resultSet.getInt(index++));
		user.setUserName(resultSet.getString(index++));
		user.setPasswordMD5(resultSet.getString(index++));
		user.setSalt(resultSet.getString(index++));
	}

	protected void load(Problem problem, ResultSet resultSet, int index) throws SQLException {
		problem.setProblemId(resultSet.getInt(index++));
		problem.setCourseId(resultSet.getInt(index++));
		problem.setProblemType(resultSet.getInt(index++));
		problem.setTestName(resultSet.getString(index++));
		problem.setBriefDescription(resultSet.getString(index++));
		problem.setDescription(resultSet.getString(index++));
	}

	protected void load(Change change, ResultSet resultSet, int index) throws SQLException {
		change.setId(resultSet.getInt(index++));
		change.setEventId(resultSet.getInt(index++));
		change.setType(resultSet.getInt(index++));
		change.setStartRow(resultSet.getInt(index++));
		change.setEndRow(resultSet.getInt(index++));
		change.setStartColumn(resultSet.getInt(index++));
		change.setEndColumn(resultSet.getInt(index++));
		change.setText(resultSet.getString(index++));
	}

	protected void load(Course course, ResultSet resultSet, int index) throws SQLException {
		course.setId(resultSet.getInt(index++));
		course.setName(resultSet.getString(index++));
		course.setTitle(resultSet.getString(index++));
		course.setUrl(resultSet.getString(index++));
		course.setTermId(resultSet.getInt(index++));
		course.setYear(resultSet.getInt(index++));
	}

	protected void load(Term term, ResultSet resultSet, int index) throws SQLException {
		term.setId(resultSet.getInt(index++));
		term.setName(resultSet.getString(index++));
		term.setSeq(resultSet.getInt(index++));
	}

	protected void load(TestCase testCase, ResultSet resultSet, int index) throws SQLException {
		testCase.setId(resultSet.getInt(index++));
		testCase.setProblemId(resultSet.getInt(index++));
		testCase.setTestCaseName(resultSet.getString(index++));
		testCase.setInput(resultSet.getString(index++));
		testCase.setOutput(resultSet.getString(index++));
	}

	protected void storeNoId(Event event, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, event.getUserId());
		stmt.setInt(index++, event.getProblemId());
		stmt.setInt(index++, event.getType());
		stmt.setLong(index++, event.getTimestamp());
	}

	protected void storeNoId(Change change, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, change.getEventId());
		stmt.setInt(index++, change.getType().ordinal());
		stmt.setInt(index++, change.getStartRow());
		stmt.setInt(index++, change.getEndRow());
		stmt.setInt(index++, change.getStartColumn());
		stmt.setInt(index++, change.getEndColumn());
		stmt.setString(index++, change.getText());
	}
}
