package com.webhook.platform.api.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Answers a protocol error as JSON-RPC instead of letting the HTTP transport turn it into a 500.
 *
 * <p>The SDK reports an unknown method, or bad params, as an {@link McpError} carrying the
 * JSON-RPC error to send — and Spring AI's stateless WebMVC transport answers every error from
 * the handler with HTTP 500. That is wrong by the spec, and it counted towards the API's 5xx
 * alert: claude.ai probes {@code server/discover}, which the SDK does not implement, so each
 * connection paged the owner.
 *
 * <p>Only an {@code McpError} with a JSON-RPC error is converted. Anything else is a real
 * failure and still reaches the transport, which answers it with a 500.
 */
final class JsonRpcErrorAnsweringTransport implements McpStatelessServerTransport {

    private final McpStatelessServerTransport delegate;

    JsonRpcErrorAnsweringTransport(McpStatelessServerTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        delegate.setMcpHandler(new McpStatelessServerHandler() {
            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(
                    McpTransportContext context, McpSchema.JSONRPCRequest request) {
                return handler.handleRequest(context, request)
                        .onErrorResume(McpError.class, error -> error.getJsonRpcError() == null
                                ? Mono.error(error)
                                : Mono.just(new McpSchema.JSONRPCResponse(
                                        McpSchema.JSONRPC_VERSION, request.id(), null, error.getJsonRpcError())));
            }

            @Override
            public Mono<Void> handleNotification(
                    McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                return handler.handleNotification(context, notification);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
