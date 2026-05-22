import os


def on_page_markdown(markdown, **kwargs):
    backend_url = os.environ.get("BACKEND_URL", "http://localhost:8080").rstrip("/")
    return markdown.replace("{{ backend_url }}", backend_url)
