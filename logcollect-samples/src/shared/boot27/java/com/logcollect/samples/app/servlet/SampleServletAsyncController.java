package com.logcollect.samples.app.servlet;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.samples.shared.SampleScenarioLogCollectHandler;
import com.logcollect.samples.shared.SampleScenarioSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/sample")
public class SampleServletAsyncController {
    private static final Logger log = LoggerFactory.getLogger(SampleServletAsyncController.class);

    @GetMapping("/servlet/async")
    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runServletAsyncScenario(HttpServletRequest request) {
        final String code = "13";
        final String title = "Servlet AsyncContext";
        SampleScenarioSupport.enterScenario(log, code, title, 4);
        final AsyncContext asyncContext = request.startAsync();
        SampleScenarioSupport.step(log, code, title, "01", "控制器已开启 AsyncContext，traceId={}",
                LogCollectContext.getCurrentTraceId());
        asyncContext.start(() -> completeAsync(asyncContext, code, title));
    }

    private void completeAsync(AsyncContext asyncContext, String code, String title) {
        try {
            SampleScenarioSupport.step(log, code, title, "03", "AsyncContext 子线程执行，线程={}，traceId={}",
                    Thread.currentThread().getName(), LogCollectContext.getCurrentTraceId());
            HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("servlet-async-ok");
            response.getWriter().flush();
            SampleScenarioSupport.step(log, code, title, "04", "AsyncContext 响应写回完成");
        } catch (IOException e) {
            throw new IllegalStateException("Servlet AsyncContext 回写失败", e);
        } finally {
            asyncContext.complete();
        }
    }
}
