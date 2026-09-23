"""Highlight code in HTML templates, which bypass Markdown's code renderer."""

import html
import re

from pygments import highlight
from pygments.formatters import HtmlFormatter
from pygments.lexers import get_lexer_by_name


CODE_BLOCK = re.compile(
    r'<pre><code class="language-([\w+-]+)">([\s\S]*?)</code></pre>'
)


def on_post_page(output, **kwargs):
    def render(match):
        language, source = match.groups()
        tokens = highlight(
            html.unescape(source),
            get_lexer_by_name(language),
            HtmlFormatter(nowrap=True),
        )
        return (
            '<div class="highlight"><pre>'
            f'<code class="language-{language}">{tokens}</code></pre></div>'
        )

    return CODE_BLOCK.sub(render, output)
