// 영역 사양 공통 동작: 캔버스마다 시안 캡처를 숨기고 보이는 버튼을 단다.
// 처음에는 보인다. 고른 상태는 보는 사람의 브라우저에만 남는다.
// 고친 뒤 node 기획/화면/공통/build.js 로 영역 사양에 다시 넣는다.
(function () {
  function setup() {
    document.querySelectorAll('.canvas').forEach(function (canvas, i) {
      var images = canvas.querySelectorAll('svg image');
      if (!images.length) return;
      var key = 'spec-capture:' + document.title + ':' + i;
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'cap-toggle';
      var shown = true;
      try { shown = localStorage.getItem(key) !== 'hidden'; } catch (e) { /* 저장소를 못 쓰면 기본값 */ }
      function apply() {
        images.forEach(function (image) { image.style.display = shown ? '' : 'none'; });
        button.textContent = shown ? '시안 캡처 숨기기' : '시안 캡처 보이기';
        button.setAttribute('aria-pressed', String(!shown));
      }
      button.addEventListener('click', function () {
        shown = !shown;
        apply();
        try { localStorage.setItem(key, shown ? 'shown' : 'hidden'); } catch (e) { /* 무시 */ }
      });
      apply();
      canvas.insertBefore(button, canvas.firstChild);
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', setup);
  else setup();
})();
