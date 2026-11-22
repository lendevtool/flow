export function createPageLoadingGuard(router) {
  let finishTimer
  router.beforeEach(() => {
    clearTimeout(finishTimer)
    $loadingBar.start()
  })

  router.afterEach(() => {
    clearTimeout(finishTimer)
    finishTimer = setTimeout(() => {
      $loadingBar.finish()
    }, 200)
  })

  router.onError(() => {
    clearTimeout(finishTimer)
    $loadingBar.error()
  })
}
