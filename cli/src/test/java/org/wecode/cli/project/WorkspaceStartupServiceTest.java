package org.wecode.cli.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.id.UuidV7IdGenerator;
import org.wecode.session.persistence.SessionDatabase;
import org.wecode.session.persistence.WorkspaceStore;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WorkspaceStartupService} 的工作区检查与确认创建测试。 */
class WorkspaceStartupServiceTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 验证已有工作区不提示、不重复创建，并以成功结果结束。
     */
    @Test
    void returnsExistsWithoutPromptWhenWorkspaceAlreadyExists() throws Exception {
        Path launchDirectory = Files.createDirectories(temporaryDirectory.resolve("project"));
        WorkspaceStore store = newStore();
        String workspacePath = launchDirectory.toRealPath().toString();
        store.create(workspacePath);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        WorkspaceStartupResult result = newService(store, "N\n", output, millis -> {
            throw new AssertionError("existing workspace must not wait before exit");
        }).start(launchDirectory);

        assertEquals(WorkspaceStartupResult.EXISTS, result);
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("[Y/N]"));
    }

    /**
     * 验证不存在的目录在用户确认后创建工作区并返回成功结果。
     */
    @Test
    void createsWorkspaceAfterAffirmativeAnswer() throws Exception {
        Path launchDirectory = Files.createDirectories(temporaryDirectory.resolve("project"));
        WorkspaceStore store = newStore();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        WorkspaceStartupResult result = newService(store, " yes \n", output, millis -> {
            throw new AssertionError("affirmative answer must not wait before exit");
        }).start(launchDirectory);

        assertEquals(WorkspaceStartupResult.CREATED, result);
        assertTrue(store.existsByAbsoluteRootPath(launchDirectory.toRealPath().toString()));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("已创建 WeCode 工作区"));
    }

    /**
     * 验证用户拒绝时不创建记录、提示已退出，并等待固定五秒。
     */
    @Test
    void cancelsAndWaitsBeforeExitAfterNegativeAnswer() throws Exception {
        Path launchDirectory = Files.createDirectories(temporaryDirectory.resolve("project"));
        WorkspaceStore store = newStore();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicLong waitedMillis = new AtomicLong(-1L);

        WorkspaceStartupResult result = newService(store, "N\n", output, waitedMillis::set).start(launchDirectory);

        assertEquals(WorkspaceStartupResult.CANCELLED, result);
        assertEquals(WorkspaceStartupService.CANCEL_EXIT_DELAY_MILLIS, waitedMillis.get());
        assertFalse(store.existsByAbsoluteRootPath(launchDirectory.toRealPath().toString()));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("已退出 WeCode，5 秒后关闭程序。"));
    }

    /**
     * 验证空输入、无效输入和 EOF 都不能被解释为创建确认。
     */
    @Test
    void cancelsAndWaitsBeforeExitWhenAnswerIsNotAffirmative() throws Exception {
        assertCancellationForAnswer("\n");
        assertCancellationForAnswer("maybe\n");
        assertCancellationForAnswer("");
    }

    /**
     * 创建独立的工作区持久化入口。
     *
     * @return 使用临时数据库目录的工作区存储
     */
    private WorkspaceStore newStore() {
        return new WorkspaceStore(
                SessionDatabase.open(temporaryDirectory.resolve("storage")),
                new UuidV7IdGenerator()
        );
    }

    /**
     * 构造使用内存输入输出的启动服务。
     *
     * @param store   工作区存储
     * @param answer  模拟的用户输入
     * @param output  捕获 CLI 输出的缓冲区
     * @param sleeper 模拟的取消等待器
     * @return 可独立测试的启动服务
     */
    private static WorkspaceStartupService newService(
            WorkspaceStore store,
            String answer,
            ByteArrayOutputStream output,
            WorkspaceStartupService.InterruptibleSleeper sleeper
    ) {
        return new WorkspaceStartupService(
                store,
                new WorkspaceResolver(),
                new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8
                )),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                sleeper
        );
    }

    /**
     * 验证指定输入会触发取消而不是创建工作区。
     *
     * @param answer 模拟的空、无效或 EOF 输入
     */
    private void assertCancellationForAnswer(String answer) throws Exception {
        Path launchDirectory = Files.createDirectories(
                temporaryDirectory.resolve("project-" + answer.length() + '-' + System.nanoTime())
        );
        WorkspaceStore store = newStore();
        AtomicLong waitedMillis = new AtomicLong(-1L);

        WorkspaceStartupResult result = newService(
                store,
                answer,
                new ByteArrayOutputStream(),
                waitedMillis::set
        ).start(launchDirectory);

        assertEquals(WorkspaceStartupResult.CANCELLED, result);
        assertEquals(WorkspaceStartupService.CANCEL_EXIT_DELAY_MILLIS, waitedMillis.get());
        assertFalse(store.existsByAbsoluteRootPath(launchDirectory.toRealPath().toString()));
    }
}
