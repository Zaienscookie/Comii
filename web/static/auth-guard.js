(function () {
  const path = location.pathname;
  if (path === '/login.html') return;
  const access = localStorage.getItem('comii_access_token') || '';
  fetch('/api/v1/auth/me', {
    credentials: 'same-origin',
    headers: access ? { 'X-Comii-Token': access } : {}
  })
    .then((response) => {
      if (response.status === 401) {
        location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
      }
    })
    .catch(() => {});
})();
