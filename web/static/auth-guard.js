(function () {
  const path = location.pathname;
  if (path === '/login.html') return;
  fetch('/api/v1/auth/me', { credentials: 'same-origin' })
    .then((response) => {
      if (response.status === 401) {
        location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
      }
    })
    .catch(() => {});
})();
