// ============================================================================
// Advanced Fingerprint Spoofing Script - FIXED
// ============================================================================
(function() {
  'use strict';
  
  console.log('[SPOOF] Starting fingerprint randomization...');
  
  // Extract fingerprint seed from URL
  function getParam(name) {
    var m = location.search.match(new RegExp("[?&]" + name + "=([^&]*)"));
    return m ? decodeURIComponent(m[1]) : "";
  }
  
  var seed = getParam("vid") || "defaultseed";
  console.log('[SPOOF] Seed:', seed.substring(0, 8));
  
  // Seeded random generator
  function seededRandom(s) {
    var h = 0;
    for (var i = 0; i < s.length; i++) {
      h = (h << 5) - h + s.charCodeAt(i);
      h |= 0;
    }
    return function() {
      h = (h * 9301 + 49297) % 233280;
      return h / 233280;
    };
  }
  
  var rng = seededRandom(seed);
  
  function randInt(min, max) {
    return Math.floor(rng() * (max - min + 1)) + min;
  }
  
  function randChoice(arr) {
    if (!arr || arr.length === 0) return null;
    var idx = randInt(0, arr.length - 1);
    return arr[idx];
  }
  
  // ========== 1. Random Screen Dimensions ==========
  var screenConfigs = [
    {w:360, h:640, dpr:2}, {w:360, h:800, dpr:3}, {w:375, h:667, dpr:2},
    {w:390, h:844, dpr:3}, {w:393, h:851, dpr:2.625}, {w:412, h:915, dpr:2.625},
    {w:414, h:896, dpr:3}, {w:428, h:926, dpr:3}, {w:360, h:780, dpr:3}
  ];
  var cfg = randChoice(screenConfigs) || screenConfigs[0];
  var screenW = cfg.w;
  var screenH = cfg.h;
  var dpr = cfg.dpr;
  var colorDepth = randChoice([24, 32]) || 24;
  
  console.log('[SPOOF] Screen:', screenW + 'x' + screenH, 'DPR:', dpr);
  
  // Override screen properties
  try {
    Object.defineProperty(screen, 'width', {get: function() { return screenW; }, configurable: true});
    Object.defineProperty(screen, 'height', {get: function() { return screenH; }, configurable: true});
    Object.defineProperty(screen, 'availWidth', {get: function() { return screenW; }, configurable: true});
    Object.defineProperty(screen, 'availHeight', {get: function() { return screenH - randInt(0, 24); }, configurable: true});
    Object.defineProperty(screen, 'colorDepth', {get: function() { return colorDepth; }, configurable: true});
    Object.defineProperty(screen, 'pixelDepth', {get: function() { return colorDepth; }, configurable: true});
    Object.defineProperty(window, 'devicePixelRatio', {get: function() { return dpr; }, configurable: true});
    Object.defineProperty(window, 'innerWidth', {get: function() { return screenW; }, configurable: true});
    Object.defineProperty(window, 'innerHeight', {get: function() { return screenH - randInt(50, 120); }, configurable: true});
  } catch(e) {
    console.log('[SPOOF] Screen override error:', e.message);
  }
  
  // ========== 2. Random Navigator Properties ==========
  var hardwareConcurrency = randChoice([4, 6, 8, 10, 12]) || 8;
  var deviceMemory = randChoice([2, 3, 4, 6, 8]) || 4;
  var maxTouchPoints = randChoice([1, 5, 10]) || 5;
  var platforms = ["Linux armv81", "Linux armv8l", "Linux aarch64", "Android"];
  var platform = randChoice(platforms) || "Android";
  
  console.log('[SPOOF] HW:', hardwareConcurrency, 'cores,', deviceMemory, 'GB RAM');
  
  try {
    Object.defineProperty(navigator, 'hardwareConcurrency', {get: function() { return hardwareConcurrency; }, configurable: true});
    if (!navigator.deviceMemory) {
      Object.defineProperty(navigator, 'deviceMemory', {get: function() { return deviceMemory; }, configurable: true});
    }
    Object.defineProperty(navigator, 'maxTouchPoints', {get: function() { return maxTouchPoints; }, configurable: true});
    Object.defineProperty(navigator, 'platform', {get: function() { return platform; }, configurable: true});
  } catch(e) {
    console.log('[SPOOF] Navigator override error:', e.message);
  }
  
  // ========== 3. Random Timezone ==========
  var timezoneOffsets = [-720, -480, -420, -360, -300, -240, -180, 0, 60, 120, 180, 300, 330, 480, 540, 600];
  var tzOffset = randChoice(timezoneOffsets) || 0;
  
  console.log('[SPOOF] Timezone offset:', tzOffset);
  
  try {
    var origGetTimezoneOffset = Date.prototype.getTimezoneOffset;
    Date.prototype.getTimezoneOffset = function() {
      return tzOffset;
    };
  } catch(e) {
    console.log('[SPOOF] Timezone override error:', e.message);
  }
  
  // ========== 4. WebGL Fingerprint Randomization ==========
  var webglVendors = [
    "Qualcomm", "ARM", "Google Inc. (Qualcomm)", "Apple Inc.", "NVIDIA Corporation",
    "Imagination Technologies", "Broadcom"
  ];
  var webglRenderers = [
    "Adreno (TM) 730", "Adreno (TM) 660", "Adreno (TM) 640", "Mali-G78", "Mali-G77",
    "Apple A15 GPU", "Apple A16 GPU", "PowerVR Rogue GE8320",
    "ANGLE (Qualcomm, Adreno (TM) 660, OpenGL ES 3.2)"
  ];
  var glVendor = randChoice(webglVendors) || "Qualcomm";
  var glRenderer = randChoice(webglRenderers) || "Adreno (TM) 660";
  
  console.log('[SPOOF] WebGL:', glVendor, '/', glRenderer);
  
  try {
    var origGetParameter = WebGLRenderingContext.prototype.getParameter;
    WebGLRenderingContext.prototype.getParameter = function(param) {
      if (param === 37445) return glVendor;
      if (param === 37446) return glRenderer;
      return origGetParameter.call(this, param);
    };
    
    if (typeof WebGL2RenderingContext !== 'undefined') {
      var origGetParameter2 = WebGL2RenderingContext.prototype.getParameter;
      WebGL2RenderingContext.prototype.getParameter = function(param) {
        if (param === 37445) return glVendor;
        if (param === 37446) return glRenderer;
        return origGetParameter2.call(this, param);
      };
    }
  } catch(e) {
    console.log('[SPOOF] WebGL override error:', e.message);
  }
  
  // ========== 5. Canvas Fingerprint Randomization ==========
  var noiseLevel = (rng() * 2 - 1) * 0.5;
  console.log('[SPOOF] Canvas noise:', noiseLevel.toFixed(3));
  
  try {
    var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL = function() {
      try {
        var ctx = this.getContext('2d');
        if (ctx && this.width > 0 && this.height > 0) {
          var imgData = ctx.getImageData(0, 0, this.width, this.height);
          for (var i = 0; i < Math.min(imgData.data.length, 400); i += 4) {
            imgData.data[i] = Math.min(255, Math.max(0, imgData.data[i] + noiseLevel));
          }
          ctx.putImageData(imgData, 0, 0);
        }
      } catch(e) {}
      return origToDataURL.apply(this, arguments);
    };
  } catch(e) {
    console.log('[SPOOF] Canvas override error:', e.message);
  }
  
  console.log('[SPOOF] ✓ Fingerprint randomization complete!');
  
})();
