/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.core.statement;

import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.junit5.DatabaseExtension;
import org.jdbi.v3.core.junit5.H2DatabaseExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

public class TestSqlLoggerCallPoints {
    private static final String CREATE = "create table foo(bar int primary key not null)";
    private static final String INSERT = "insert into foo(bar) values(1)";
    private static final String INSERT_NULL = "insert into foo(bar) values(null)";
    private static final String INSERT_PREPARED = "insert into foo(bar) values(?)";
    // TODO can apparently be changed to > 0 with jdk9
    private static final Predicate<Long> IS_POSITIVE = x -> x >= 0;

    @RegisterExtension
    public DatabaseExtension h2Extension = H2DatabaseExtension.instance();

    private Handle h;
    private TalkativeSqlLogger logger;

    @BeforeEach
    public void before() {
        logger = new TalkativeSqlLogger();
        h2Extension.getJdbi().getConfig(SqlStatements.class).setSqlLogger(logger);
        h = h2Extension.getJdbi().open();
    }

    @AfterEach
    public void after() {
        h.close();
    }

    @Test
    public void testStatement() {
        h.execute(CREATE);
        h.createUpdate(INSERT).execute();

        assertThat(logger.getRawSql()).containsExactly(CREATE, CREATE, INSERT, INSERT);
        assertThat(logger.getTimings()).hasSize(2).allMatch(IS_POSITIVE);
        assertThat(logger.getExceptions()).isEmpty();
    }

    @Test
    public void testStatementException() {
        h.execute(CREATE);

        Throwable e = catchThrowable(h.createUpdate(INSERT_NULL)::execute);

        assertThat(e)
            .isInstanceOf(RuntimeException.class)
            .hasCauseInstanceOf(SQLException.class);

        assertThat(logger.getRawSql()).containsExactly(CREATE, CREATE, INSERT_NULL, INSERT_NULL);
        assertThat(logger.getTimings()).hasSize(2).allMatch(IS_POSITIVE);
        assertThat(logger.getExceptions()).containsExactly((SQLException) e.getCause());
    }

    @Test
    public void testBatch() {
        h.execute(CREATE);

        h.createBatch().add(INSERT).execute();

        // unfortunately...
        assertThat(logger.getRawSql()).containsExactly(CREATE, CREATE, null, null);
        assertThat(logger.getTimings()).hasSize(2).allMatch(IS_POSITIVE);
        assertThat(logger.getExceptions()).isEmpty();
    }

    @Test
    public void testBatchException() {
        h.execute(CREATE);

        Throwable e = catchThrowable(h.createBatch().add(INSERT_NULL)::execute);

        // unfortunately...
        assertThat(logger.getRawSql()).containsExactly(CREATE, CREATE, null, null);
        assertThat(logger.getTimings()).hasSize(2).allMatch(IS_POSITIVE);
        assertThat(logger.getExceptions()).containsExactly((SQLException) e.getCause());
    }

    @Test
    public void testPreparedBatch() {
        h.execute(CREATE);

        h.prepareBatch(INSERT_PREPARED).bind(0, 1).execute();

        assertThat(logger.getRawSql()).containsExactly(CREATE, CREATE, INSERT_PREPARED, INSERT_PREPARED);
        assertThat(logger.getTimings()).hasSize(2).allMatch(IS_POSITIVE);
        assertThat(logger.getExceptions()).isEmpty();
    }

    @Test
    public void testPreparedBatchException() {
        h.execute(CREATE);

        Throwable e = catchThrowable(h.prepareBatch(INSERT_PREPARED).bindByType(0, null, Integer.class)::execute);

        assertThat(logger.getRawSql()).containsExactly(CREATE, CREATE, INSERT_PREPARED, INSERT_PREPARED);
        assertThat(logger.getTimings()).hasSize(2).allMatch(IS_POSITIVE);
        assertThat(logger.getExceptions()).containsExactly((SQLException) e.getCause());
    }

    @Test
    public void testNotSql() {
        String query = "herp derp";

        assertThatThrownBy(() -> h.execute(query)).isNotNull();

        assertThat(logger.getRawSql()).isEmpty();
        assertThat(logger.getTimings()).isEmpty();
        assertThat(logger.getExceptions()).isEmpty();
    }

    private static class TalkativeSqlLogger implements SqlLogger {
        private final List<String> rawSql = new ArrayList<>();
        private final List<Long> timings = new ArrayList<>();
        private final List<SQLException> exceptions = new ArrayList<>();

        @Override
        public void logBeforeExecution(StatementContext context) {
            rawSql.add(context.getRawSql());
        }

        @Override
        public void logAfterExecution(StatementContext context) {
            rawSql.add(context.getRawSql());
            timings.add(context.getElapsedTime(ChronoUnit.NANOS));
        }

        @Override
        public void logException(StatementContext context, SQLException ex) {
            rawSql.add(context.getRawSql());
            exceptions.add(ex);
            timings.add(context.getElapsedTime(ChronoUnit.NANOS));
        }

        public List<String> getRawSql() {
            return rawSql;
        }

        public List<Long> getTimings() {
            return timings;
        }

        public List<SQLException> getExceptions() {
            return exceptions;
        }
    }
}
