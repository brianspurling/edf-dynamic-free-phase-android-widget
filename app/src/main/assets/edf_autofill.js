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

    // Wait one tick so React picks up the controlled-value update before we submit.
    setTimeout(function submit() {
      // Prefer form.requestSubmit() — fires a real submit event so React's onSubmit handler runs.
      const form = input.closest('form') || document.querySelector('form');
      if (form && typeof form.requestSubmit === 'function') {
        form.requestSubmit();
        return;
      }
      // Fallback: click the submit button if the form isn't requestSubmit-able for some reason.
      const buttons = Array.from(document.querySelectorAll('button[type="submit"]'));
      const target =
        buttons.find(b => /view\s+unit\s+prices/i.test((b.textContent || '').trim())) ||
        buttons[0];
      if (target) {
        target.click();
        return;
      }
      if (Date.now() < deadline) setTimeout(submit, 150);
    }, 80);
  }

  tryFill();
})();
