// Все строки интерфейса — здесь, на русском и английском. Не пишите текст прямо в компонентах.
// Подстановки — `{имя}`, их заполняет format(). У английского набора те же ключи, что у русского
// (это проверяет TypeScript), поэтому забыть перевод нельзя.

export type Language = 'ru' | 'en'

const ru = {
  appName: 'Рядом',
  sourceCode: 'Исходный код',
  loading: 'Загрузка…',
  retry: 'Повторить',
  logout: 'Выйти',
  signedInAs: 'Вы вошли как {name}',
  devOnly: 'Только для разработки',

  errorGeneric: 'Что-то пошло не так. Попробуйте ещё раз.',
  errorNetwork: 'Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.',
  errorSessionExpired: 'Сеанс истёк. Войдите снова.',
  errorBanned: 'Ваш аккаунт заблокирован.',
  errorOauthFailed: 'Не удалось войти через Яндекс ID. Попробуйте ещё раз.',
  errorOauthDenied: 'Вход через Яндекс ID отменён.',
  errorProviderUnavailable: 'Яндекс ID сейчас недоступен. Попробуйте позже.',
  errorLoginNotFound: 'Этот способ входа не включён на сервере.',
  errorInvalidLogin: 'Логин может содержать только латинские буквы, цифры, «-» и «_» (до 32 символов).',
  errorRequestTaken: 'Этот вызов уже принял другой волонтёр.',
  errorRequestClosed: 'Этот вызов уже закрыт.',
  errorAlreadyInCall: 'У вас уже идёт звонок.',
  errorActiveRequestRole: 'Сначала завершите текущий запрос или звонок.',
  errorTooManyRequests: 'Слишком много запросов. Попробуйте позже.',

  offlineTitle: 'Нет связи с сервером',

  loginTitle: 'Вход для волонтёров',
  loginIntro:
    'Волонтёры отвечают на видеозвонки незрячих людей и помогают: прочитать надпись, найти вещь, разобраться с техникой.',
  loginWithYandex: 'Войти через Яндекс ID',
  loginUnavailable: 'Вход пока не настроен на этом сервере.',
  loggingIn: 'Входим…',
  devLoginTitle: 'Вход без Яндекс ID',
  devLoginLabel: 'Логин',
  devLoginHint: 'Латинские буквы, цифры, «-» и «_», например volunteer-1. Работает, только если на сервере AUTH_DEV_ENABLED=true.',
  devLoginSubmit: 'Войти',

  roleTitle: 'Станьте волонтёром',
  roleIntro: 'Веб-версия — для волонтёров. Незрячим людям нужно приложение для Android.',
  becomeVolunteer: 'Стать волонтёром',
  roleBlindTitle: 'Этот аккаунт — для просьб о помощи',
  roleBlindText:
    'Веб-версия — только для волонтёров. Чтобы попросить помощи, используйте приложение для Android. Если вы хотите помогать сами, смените роль.',
  roleAdminTitle: 'Админка появится позже',
  roleAdminText: 'Сейчас в веб-версии есть только кабинет волонтёра.',
  roleUnknownTitle: 'Веб-версия не поддерживает вашу роль',
  roleUnknownText: 'Возможно, сайт устарел. Обновите страницу.',
  devBecomeBlind: 'Стать тестовым незрячим',
  devBlindHint: 'Тестовый незрячий создаёт запросы помощи и показывает камеру этого компьютера — так звонок можно проверить без Android-приложения.',

  volunteerTitle: 'Кабинет волонтёра',
  readyLabel: 'Готов помогать',
  readyHintOn: 'Вызовы приходят, пока эта вкладка открыта.',
  readyHintOff: 'Вызовы не приходят. Включите, когда будете готовы помочь.',
  readyOnAnnouncement: 'Вызовы включены',
  readyOffAnnouncement: 'Вызовы выключены',
  connectionConnecting: 'Подключаемся к серверу…',
  connectionConnected: 'На связи с сервером.',
  connectionReconnecting: 'Нет связи с сервером. Переподключаемся…',
  soundHint: 'Входящий вызов сопровождается звуком. Браузер разрешает звук только после нажатия на странице — проверьте, что он слышен.',
  soundTest: 'Проверить звук',

  quietTitle: 'Время тишины',
  quietWindow: 'С {from} до {to} по часовому поясу {timezone} вызовы не приходят.',
  quietNone: 'Вызовы приходят в любое время суток.',
  quietNow: 'Сейчас время тишины: вызовы не придут до {to}.',
  quietEdit: 'Изменить время тишины',
  quietFrom: 'Начало',
  quietTo: 'Конец',
  quietOff: 'Принимать вызовы круглосуточно',
  save: 'Сохранить',
  cancel: 'Отмена',
  saved: 'Сохранено',

  incomingTitle: 'Входящие вызовы',
  incomingNone: 'Новых вызовов нет.',
  incomingCard: 'Нужна помощь',
  incomingLanguage: 'Язык: {language}',
  accept: 'Принять',
  accepting: 'Принимаем…',
  skip: 'Пропустить',
  incomingAnnouncement: 'Входящий вызов: нужна помощь',
  incomingTabTitle: 'Вызов! — Рядом',
  noticeTaken: 'Вызов принял другой волонтёр.',
  noticeCancelled: 'Вызов отменён.',
  noticeNoAnswer: 'Вызов больше не ждёт ответа.',
  languageRu: 'русский',
  languageEn: 'английский',
  languageUnknown: 'другой',

  callTitle: 'Звонок',
  callWith: 'Собеседник: {name}',
  callConnecting: 'Подключаемся к звонку…',
  callWaiting: 'Ждём, пока собеседник подключится…',
  callActive: 'Звонок идёт.',
  callReconnecting: 'Связь прервалась. Переподключаемся…',
  callRemoteLeft: 'Собеседник отключился. Можно подождать или завершить звонок.',
  callDisconnected: 'Соединение со звонком потеряно.',
  callReplaced: 'Звонок открыт в другой вкладке.',
  callReconnect: 'Подключиться снова',
  remoteVideoLabel: 'Видео с камеры собеседника',
  localVideoLabel: 'Ваша камера',
  noVideo: 'Видео пока нет',
  micMute: 'Выключить микрофон',
  micUnmute: 'Включить микрофон',
  micMutedAnnouncement: 'Микрофон выключен',
  micOnAnnouncement: 'Микрофон включён',
  micBlocked: 'Нет доступа к микрофону. Разрешите его в настройках браузера и нажмите «Включить микрофон».',
  cameraBlocked: 'Нет доступа к камере. Разрешите её в настройках браузера и обновите страницу.',
  audioBlocked: 'Браузер не включил звук собеседника.',
  enableAudio: 'Включить звук',
  endCall: 'Завершить звонок',
  ending: 'Завершаем…',

  callEndedTitle: 'Звонок завершён',
  callEndedByOther: 'Собеседник завершил звонок.',
  ratingQuestion: 'Удалось помочь?',
  ratingYes: 'Да',
  ratingNo: 'Нет',
  ratingSkip: 'Пропустить',
  ratingThanks: 'Спасибо!',

  devBlindTitle: 'Тестовый незрячий',
  devBlindIntro:
    'Страница для проверки звонка без Android-приложения. Волонтёр входит в окне инкогнито или в другом браузере: вкладки одного окна делят сохранённый вход.',
  requestHelp: 'Попросить помощи',
  searching: 'Ищем волонтёра…',
  cancelRequest: 'Отменить',
  searchCancelled: 'Запрос отменён.',
  noAnswer: 'Сейчас никто не ответил. Попробуйте ещё раз.',
  volunteerFound: 'Волонтёр найден. Подключаемся…',
}

export type Strings = Record<keyof typeof ru, string>
export type StringKey = keyof Strings

const en: Strings = {
  appName: 'Ryadom',
  sourceCode: 'Source code',
  loading: 'Loading…',
  retry: 'Try again',
  logout: 'Sign out',
  signedInAs: 'Signed in as {name}',
  devOnly: 'For development only',

  errorGeneric: 'Something went wrong. Please try again.',
  errorNetwork: 'Cannot reach the server. Check your connection and try again.',
  errorSessionExpired: 'Your session has expired. Please sign in again.',
  errorBanned: 'Your account has been blocked.',
  errorOauthFailed: 'Could not sign in with Yandex ID. Please try again.',
  errorOauthDenied: 'Yandex ID sign-in was cancelled.',
  errorProviderUnavailable: 'Yandex ID is unavailable right now. Please try later.',
  errorLoginNotFound: 'This sign-in method is not enabled on the server.',
  errorInvalidLogin: 'The login may contain only Latin letters, digits, "-" and "_" (up to 32 characters).',
  errorRequestTaken: 'Another volunteer has already accepted this call.',
  errorRequestClosed: 'This call is already closed.',
  errorAlreadyInCall: 'You are already in a call.',
  errorActiveRequestRole: 'Finish the current request or call first.',
  errorTooManyRequests: 'Too many requests. Please try later.',

  offlineTitle: 'Cannot reach the server',

  loginTitle: 'Volunteer sign-in',
  loginIntro: 'Volunteers answer video calls from blind people and help them read a label, find an item or figure out a device.',
  loginWithYandex: 'Sign in with Yandex ID',
  loginUnavailable: 'Sign-in is not configured on this server yet.',
  loggingIn: 'Signing in…',
  devLoginTitle: 'Sign in without Yandex ID',
  devLoginLabel: 'Login',
  devLoginHint: 'Latin letters, digits, "-" and "_", for example volunteer-1. Works only if the server has AUTH_DEV_ENABLED=true.',
  devLoginSubmit: 'Sign in',

  roleTitle: 'Become a volunteer',
  roleIntro: 'The web version is for volunteers. Blind people need the Android app.',
  becomeVolunteer: 'Become a volunteer',
  roleBlindTitle: 'This account is for asking for help',
  roleBlindText:
    'The web version is for volunteers only. To ask for help, use the Android app. If you want to help others yourself, change your role.',
  roleAdminTitle: 'The admin panel is coming later',
  roleAdminText: 'For now the web version has only the volunteer page.',
  roleUnknownTitle: 'The web version does not support your role',
  roleUnknownText: 'The site may be out of date. Please reload the page.',
  devBecomeBlind: 'Become a test blind user',
  devBlindHint: 'A test blind user creates help requests and shows this computer’s camera, so you can try a call without the Android app.',

  volunteerTitle: 'Volunteer page',
  readyLabel: 'Ready to help',
  readyHintOn: 'You receive calls while this tab is open.',
  readyHintOff: 'You do not receive calls. Turn this on when you are ready to help.',
  readyOnAnnouncement: 'Calls turned on',
  readyOffAnnouncement: 'Calls turned off',
  connectionConnecting: 'Connecting to the server…',
  connectionConnected: 'Connected to the server.',
  connectionReconnecting: 'No connection to the server. Reconnecting…',
  soundHint: 'An incoming call plays a sound. Browsers allow sound only after you click on the page, so check that you can hear it.',
  soundTest: 'Test the sound',

  quietTitle: 'Quiet hours',
  quietWindow: 'From {from} to {to} ({timezone} time) you do not receive calls.',
  quietNone: 'You receive calls at any time of day.',
  quietNow: 'Quiet hours now: no calls until {to}.',
  quietEdit: 'Change quiet hours',
  quietFrom: 'Start',
  quietTo: 'End',
  quietOff: 'Receive calls around the clock',
  save: 'Save',
  cancel: 'Cancel',
  saved: 'Saved',

  incomingTitle: 'Incoming calls',
  incomingNone: 'No new calls.',
  incomingCard: 'Someone needs help',
  incomingLanguage: 'Language: {language}',
  accept: 'Accept',
  accepting: 'Accepting…',
  skip: 'Skip',
  incomingAnnouncement: 'Incoming call: someone needs help',
  incomingTabTitle: 'Call! — Ryadom',
  noticeTaken: 'Another volunteer accepted the call.',
  noticeCancelled: 'The call was cancelled.',
  noticeNoAnswer: 'The call is no longer waiting for an answer.',
  languageRu: 'Russian',
  languageEn: 'English',
  languageUnknown: 'other',

  callTitle: 'Call',
  callWith: 'Talking to {name}',
  callConnecting: 'Connecting to the call…',
  callWaiting: 'Waiting for the other person to join…',
  callActive: 'Call in progress.',
  callReconnecting: 'Connection lost. Reconnecting…',
  callRemoteLeft: 'The other person disconnected. You can wait or end the call.',
  callDisconnected: 'Lost connection to the call.',
  callReplaced: 'The call is open in another tab.',
  callReconnect: 'Reconnect',
  remoteVideoLabel: 'Video from the other person’s camera',
  localVideoLabel: 'Your camera',
  noVideo: 'No video yet',
  micMute: 'Turn microphone off',
  micUnmute: 'Turn microphone on',
  micMutedAnnouncement: 'Microphone off',
  micOnAnnouncement: 'Microphone on',
  micBlocked: 'No access to the microphone. Allow it in the browser settings and press “Turn microphone on”.',
  cameraBlocked: 'No access to the camera. Allow it in the browser settings and reload the page.',
  audioBlocked: 'The browser did not turn on the other person’s sound.',
  enableAudio: 'Turn on sound',
  endCall: 'End call',
  ending: 'Ending…',

  callEndedTitle: 'Call ended',
  callEndedByOther: 'The other person ended the call.',
  ratingQuestion: 'Were you able to help?',
  ratingYes: 'Yes',
  ratingNo: 'No',
  ratingSkip: 'Skip',
  ratingThanks: 'Thank you!',

  devBlindTitle: 'Test blind user',
  devBlindIntro:
    'A page to try a call without the Android app. The volunteer signs in in a private window or another browser, because tabs of one window share the saved sign-in.',
  requestHelp: 'Ask for help',
  searching: 'Looking for a volunteer…',
  cancelRequest: 'Cancel',
  searchCancelled: 'Request cancelled.',
  noAnswer: 'Nobody answered right now. Please try again.',
  volunteerFound: 'A volunteer accepted. Connecting…',
}

export const strings: Record<Language, Strings> = { ru, en }

/** Подставляет значения в строку: format('С {from}', { from: '22:00' }) → 'С 22:00'. */
export function format(template: string, values: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (match, name: string) => values[name] ?? match)
}

/** Выбирает язык по настройкам браузера; по умолчанию — русский. */
export function detectLanguage(preferred: readonly string[]): Language {
  for (const tag of preferred) {
    const base = tag.toLowerCase().split('-')[0]
    if (base === 'ru' || base === 'en') return base
  }
  return 'ru'
}

/**
 * Применяет язык ко всей странице: атрибут lang (по нему экранный диктор выбирает голос)
 * и заголовок вкладки. В index.html заголовок на русском — он виден только до загрузки скрипта.
 */
export function applyLanguageToDocument(doc: Document, language: Language): void {
  doc.documentElement.lang = language
  doc.title = strings[language].appName
}
