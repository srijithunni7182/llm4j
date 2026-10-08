import os

html_template = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Nirmaan Yantra Article</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/github-markdown-css/5.2.0/github-markdown.min.css">
<style>
    body {
        box-sizing: border-box;
        min-width: 200px;
        max-width: 980px;
        margin: 0 auto;
        padding: 45px;
        font-family: -apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans",Helvetica,Arial,sans-serif,"Apple Color Emoji","Segoe UI Emoji";
    }
    @media (max-width: 767px) {
        body {
            padding: 15px;
        }
    }
</style>
</head>
<body>
<article class="markdown-body" id="content">
</article>
<script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
<script>
    const markdownContent = `
%s
`;
    document.getElementById('content').innerHTML = marked.parse(markdownContent);
</script>
</body>
</html>
"""

try:
    with open('ARTICLE.md', 'r') as f:
        content = f.read()

    # Escape backticks and backslashes for JS template literal
    # Order matters: escape backslashes first, then backticks, then template literal placeholder
    content = content.replace('\\', '\\\\').replace('`', '\\`').replace('${', '\\${')

    with open('ARTICLE.html', 'w') as f:
        f.write(html_template % content)

    print("Successfully generated ARTICLE.html")
except Exception as e:
    print(f"Error: {e}")
