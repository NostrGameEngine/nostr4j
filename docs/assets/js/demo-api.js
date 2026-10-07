(function () {
  var configured = document.querySelector('meta[name="demo-api-base"]');
  var base = configured && configured.content.trim();

  window.demoApiUrl = function (path) {
    return new URL(path, base || window.location.origin).toString();
  };
})();
