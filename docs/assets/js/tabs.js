document.querySelectorAll('[data-tabs]').forEach(group => {
  const tabs = [...group.querySelectorAll(':scope > [role="tablist"] [role="tab"]')];
  function select(tab, focus = false) {
    for (const item of tabs) {
      const active = item === tab;
      item.setAttribute('aria-selected', String(active));
      item.tabIndex = active ? 0 : -1;
      document.getElementById(item.getAttribute('aria-controls')).hidden = !active;
    }
    if (focus) tab.focus();
    group.dispatchEvent(new CustomEvent('tabchange', {detail: tab.id}));
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => select(tab));
    tab.addEventListener('keydown', event => {
      let next;
      const vertical = group.querySelector(':scope > [role="tablist"]').getAttribute('aria-orientation') === 'vertical';
      if (event.key === 'ArrowRight' || (vertical && event.key === 'ArrowDown')) next = (index + 1) % tabs.length;
      if (event.key === 'ArrowLeft' || (vertical && event.key === 'ArrowUp')) next = (index - 1 + tabs.length) % tabs.length;
      if (event.key === 'Home') next = 0;
      if (event.key === 'End') next = tabs.length - 1;
      if (next !== undefined) { event.preventDefault(); select(tabs[next], true); }
    });
  });
  const requested = tabs.find(tab => tab.getAttribute('aria-controls') === location.hash.slice(1));
  if (requested) select(requested);
});
