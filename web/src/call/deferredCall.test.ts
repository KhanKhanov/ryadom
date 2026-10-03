import { callJson, FakeCall } from '../testing/fakes'
import type { CallState } from './call'
import { DeferredCall } from './deferredCall'

describe('DeferredCall', () => {
  it('passes calls to the session once it has loaded', async () => {
    const real = new FakeCall(callJson, { publishCamera: false })
    const deferred = new DeferredCall(Promise.resolve(real))
    const states: CallState[] = []
    const video = document.createElement('video')

    deferred.subscribe((state) => states.push(state))
    deferred.attachVideo('remote', video)
    await deferred.connect()
    await deferred.setMicrophoneEnabled(false)
    await deferred.startAudio()

    expect(real.connected).toBe(true)
    expect(real.videos.remote).toBe(video)
    expect(real.microphoneCalls).toEqual([false])
    expect(real.audioStarted).toBe(true)
    expect(states.at(-1)?.microphone).toBe('muted')

    await deferred.disconnect()
    expect(real.disconnected).toBe(true)
  })

  it('stops passing states after unsubscribing', async () => {
    const real = new FakeCall(callJson, { publishCamera: false })
    const deferred = new DeferredCall(Promise.resolve(real))
    const states: CallState[] = []

    const unsubscribe = deferred.subscribe((state) => states.push(state))
    await deferred.connect()
    unsubscribe()
    real.update({ connection: 'connected' })

    expect(states.some((state) => state.connection === 'connected')).toBe(false)
  })

  it('reports a lost connection when LiveKit could not be loaded', async () => {
    const deferred = new DeferredCall(Promise.reject(new Error('chunk failed')))
    const states: CallState[] = []

    deferred.subscribe((state) => states.push(state))
    await expect(deferred.connect()).rejects.toThrow('chunk failed')

    expect(states.at(-1)?.connection).toBe('disconnected')
    await expect(deferred.disconnect()).resolves.toBeUndefined()
  })
})
