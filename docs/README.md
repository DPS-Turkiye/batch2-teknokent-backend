# Backend documentation

| Document | Read it to |
|---|---|
| [Development](development.md) | set up a machine, run the stack, run and write tests, use the Makefile |
| [Architecture](architecture.md) | find where code lives, how a request flows, what runs in the background |
| [Security](security.md) | understand who a caller is, how access is checked, and the limits on abuse |
| [AI features](ai.md) | see what goes to the AI provider, how each feature can be switched off, and the fallbacks |
| [Configuration](configuration.md) | look up an environment variable |
| [Deployment](deployment.md) | follow CI, a release and what runs in production |

The rules every change follows (layers, errors, time zones, texts, null safety) are in [AGENTS.md](../AGENTS.md);
the build enforces most of them. Local multi-edition and Moodle setups have their own guides in
[dev/editions](../dev/editions/README.md) and [dev/moodle](../dev/moodle/README.md).
