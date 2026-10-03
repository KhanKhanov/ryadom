import { hasQuietHours, isInWindow, isQuietNow, localTime } from './quietHours'

describe('quiet hours', () => {
  // Те же случаи, что у сервера (VolunteerMatcherTest): интерфейс должен говорить то же, что решает сервер.
  it('checks a window inside one day', () => {
    expect(isInWindow('12:59', '13:00', '15:00')).toBe(false)
    expect(isInWindow('13:00', '13:00', '15:00')).toBe(true)
    expect(isInWindow('14:59', '13:00', '15:00')).toBe(true)
    expect(isInWindow('15:00', '13:00', '15:00')).toBe(false)
  })

  it('checks a window across midnight', () => {
    expect(isInWindow('21:59', '22:00', '08:00')).toBe(false)
    expect(isInWindow('22:00', '22:00', '08:00')).toBe(true)
    expect(isInWindow('00:30', '22:00', '08:00')).toBe(true)
    expect(isInWindow('08:00', '22:00', '08:00')).toBe(false)
  })

  it('treats equal start and end as no quiet hours', () => {
    expect(hasQuietHours({ from: '00:00', to: '00:00' })).toBe(false)
    expect(isInWindow('00:00', '00:00', '00:00')).toBe(false)
    expect(hasQuietHours({ from: '22:00', to: '08:00' })).toBe(true)
  })

  it('uses the volunteer’s time zone', () => {
    const now = new Date('2026-10-03T20:30:00Z')
    expect(localTime(now, 'Europe/Moscow')).toBe('23:30')
    expect(localTime(now, 'Asia/Vladivostok')).toBe('06:30')
    expect(isQuietNow({ from: '22:00', to: '08:00' }, 'Europe/Moscow', now)).toBe(true)
    expect(isQuietNow({ from: '22:00', to: '08:00' }, 'Europe/London', now)).toBe(false)
  })

  it('does not claim quiet hours for a time zone the browser does not know', () => {
    expect(localTime(new Date(), 'Mars/Olympus')).toBeNull()
    expect(isQuietNow({ from: '00:00', to: '23:59' }, 'Mars/Olympus', new Date())).toBe(false)
  })
})
