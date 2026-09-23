import './tabs.js';
const exampleCode = document.querySelector('.home-demo .source-panel pre');
if (exampleCode) {
  exampleCode.tabIndex = 0;
  exampleCode.setAttribute('aria-label', 'Java quick start example; scroll to read all lines');
}
const button = document.getElementById('quickstart-run');
const output = document.getElementById('quickstart-output');
let loaded = false;
button.addEventListener('click', async () => {
  button.disabled = true;
  output.hidden = false;
  output.textContent = 'Loading Java example…\n';
  try {
    if (!loaded) {
      const module = await import('../demos/quickstart/demo.js');
      module.main([]);
      loaded = true;
    }
    window.QuickStartDemo.run(
      line => { output.textContent += line + '\n'; },
      () => { button.disabled = false; }
    );
  } catch (error) {
    output.textContent += 'Could not run the example: ' + error.message;
    button.disabled = false;
  }
});
