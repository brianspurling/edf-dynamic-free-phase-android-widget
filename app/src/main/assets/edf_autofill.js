(function () {
  const POSTCODE = "__POSTCODE__"; // replaced at runtime by Android
  const deadline = Date.now() + 5000;

  function setNativeValue(input, value) {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
    setter.call(input, value);
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function tryFill() {
    if (Date.now() > deadline) return;
    const input = document.querySelector('input[name="postcode"]');
    if (!input) return setTimeout(tryFill, 150);

    setNativeValue(input, POSTCODE);

    // wait one tick so React picks up the controlled-value update
    setTimeout(function clickSubmit() {
      const buttons = Array.from(document.querySelectorAll('button[type="submit"]'));
      const target = buttons.find(b => /view\s+unit\s+prices/i.test(b.textContent || ""));
      if (target) { target.click(); return; }
      if (Date.now() < deadline) setTimeout(clickSubmit, 150);
    }, 80);
  }

  tryFill();
})();
