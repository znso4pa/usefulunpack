# Nested containers and bounded extraction

1. Identify the outer layer and list what is inside. Record the parent path and expected inner format before starting another extraction.
2. Use a new directory per layer and extract only the candidates needed. Check available space and declared sizes before processing a chain of archives.
3. Inspect each inner payload separately. Record which layer failed and preserve the parent archive. Continue only while another layer is relevant to the user's task.
