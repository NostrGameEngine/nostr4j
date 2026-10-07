const button = document.getElementById('publish-send');
const input = document.getElementById('publish-content');
const output = document.getElementById('publish-output');
let loaded = false;

button.addEventListener('click', async () => {
  const content = input.value.trim();
  if (!content) { output.textContent = 'Write a note before publishing.'; return; }
  if (new TextEncoder().encode(content).length > 1024) {
    output.textContent = 'The note must be at most 1024 UTF-8 bytes.';
    return;
  }
  button.disabled = true;
  output.textContent = 'Loading the compiled Java publisher…\n';
  try {
    if (!loaded) {
      const module = await import('../demos/quickstart/demo.js');
      module.main([]);
      loaded = true;
    }
    window.QuickStartDemo.publish(
      content,
      line => { output.textContent += line + '\n'; },
      () => { button.disabled = false; }
    );
  } catch (error) {
    output.textContent += 'Could not publish: ' + error.message;
    button.disabled = false;
  }
});
