window.__ModuleLoader__.load({
    id: 'dsh-love2droid',
    factory: () => {
        // Locale resources use the same registry as the built-in Web plugins.
        const messages = {
            zh: { cancelled: '文件打开已取消。', disconnected: '文件打开连接已断开。' },
            en: { cancelled: 'File opening cancelled.', disconnected: 'File opener disconnected.' },
        };
        function apply(ctx) {
            const bridge = window.Love2DroidFiles;
            // Desktop browsers keep their native opener, including xdg-open.
            if (!bridge || window !== window.top) return;
            ctx.effect(() => ctx.locale.register('love2droid', messages), 'love2droid: dictionaries');
            const t = ctx.locale.bind('love2droid');
            const rpc = ctx.connection.rpc;
            const original = rpc.call;
            const pending = new Map();
            let sequence = 0;
            const failure = (message, code = 'gateway/internal') => ({
                ok: false, error: { code, message, details: {} },
            });
            const openInEditor = (path, signal) => new Promise(resolve => {
                const id = String(++sequence);
                const finish = result => {
                    pending.delete(id);
                    signal?.removeEventListener('abort', cancel);
                    resolve(result);
                };
                const cancel = () => finish(failure(t('cancelled'), 'gateway/cancelled'));
                if (signal?.aborted) return cancel();
                pending.set(id, finish);
                signal?.addEventListener('abort', cancel, { once: true });
                try {
                    bridge.postMessage(JSON.stringify({ id, path }));
                } catch (error) {
                    finish(failure(error.message));
                }
            });
            const onMessage = event => {
                const response = JSON.parse(event.data);
                pending.get(response.id)?.(response.error
                    ? failure(response.error)
                    : { ok: true, value: { opened: true } });
            };
            const previousMessage = bridge.onmessage;
            const call = async (channel, endpoint, payload, signal) => {
                if (channel !== '/api') return original.call(rpc, channel, endpoint, payload, signal);
                if (endpoint === 'session/canOpenWorkspacePath') return { ok: true, value: true };
                if (endpoint !== 'session/openWorkspacePath') return original.call(rpc, channel, endpoint, payload, signal);
                const path = payload?.args?.request?.path;
                // Web links and folder actions retain their original behavior.
                if (typeof path === 'string' && /^https?:/i.test(path)) {
                    return original.call(rpc, channel, endpoint, payload, signal);
                }
                const resolved = await original.call(rpc, '/love2droid', 'resolve-file', { path }, signal);
                if (!resolved.ok) return resolved;
                if (resolved.value.kind === 'directory') return original.call(rpc, channel, endpoint, payload, signal);
                return openInEditor(resolved.value.path, signal);
            };
            ctx.effect(() => {
                bridge.onmessage = onMessage;
                rpc.call = call;
                return () => {
                    if (rpc.call === call) rpc.call = original;
                    if (bridge.onmessage === onMessage) bridge.onmessage = previousMessage;
                    for (const finish of pending.values()) finish(failure(t('disconnected'), 'gateway/cancelled'));
                };
            }, 'love2droid: WebView file opener');
        }
        return { inject: ['connection', 'locale'], apply };
    },
});
