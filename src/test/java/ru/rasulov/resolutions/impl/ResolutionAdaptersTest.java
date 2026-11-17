package ru.rasulov.resolutions.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.resolutions.api.ResolutionService;
import ru.rasulov.resolutions.api.dto.CreateResolutionRequest;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ResolutionAdaptersTest {
    private final ResolutionService service = mock(ResolutionService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test public void providerRegistersBothToolsAndPassesCatalogQuery() throws Exception {
        ResolutionTools provider = new ResolutionTools(service);
        assertEquals(new HashSet<>(Arrays.asList("get_resolutions", "create_resolution")), provider.tools().keySet());
        provider.tools().get("get_resolutions").call(mapper.readTree("{\"query\":\"Fixed\"}"));
        verify(service).get("Fixed");
    }

    @Test public void createToolPassesExplicitPreviewAndApplyModes() throws Exception {
        ResolutionTools provider = new ResolutionTools(service);
        provider.tools().get("create_resolution").call(mapper.readTree("{\"dryRun\":true,\"name\":\"Completed\",\"description\":\"Finished\"}"));
        provider.tools().get("create_resolution").call(mapper.readTree("{\"dryRun\":false,\"planId\":\"confirmed\"}"));
        verify(service).create(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && "Completed".equals(request.getName()) && "Finished".equals(request.getDescription())));
        verify(service).create(argThat(request -> Boolean.FALSE.equals(request.isDryRun())
                && "confirmed".equals(request.getPlanId()) && request.getName() == null));
    }

    @Test(expected = InvalidRequestException.class) public void catalogToolRejectsUnknownArguments() throws Exception {
        try { new ResolutionTools(service).tools().get("get_resolutions").call(mapper.readTree("{\"limit\":1}")); }
        finally { verifyNoInteractions(service); }
    }

    @Test(expected = InvalidRequestException.class) public void createToolRejectsUnknownArguments() throws Exception {
        try { new ResolutionTools(service).tools().get("create_resolution").call(mapper.readTree("{\"dryRun\":true,\"name\":\"Completed\",\"unexpected\":true}")); }
        finally { verifyNoInteractions(service); }
    }
}
