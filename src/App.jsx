import { useEffect, useState } from 'react'
import { Capacitor, registerPlugin } from '@capacitor/core'
import './App.css'

const Overlay = registerPlugin('Overlay')

const languages = [
  'עברית',
  'English',
  'Español',
  'Français',
  'Deutsch',
  'Italiano',
  'Português',
  'Русский',
  'العربية',
  'Türkçe',
  '中文',
  '日本語',
  '한국어',
]

function App() {
  const [targetLanguage, setTargetLanguage] = useState('עברית')
  const [isActive, setIsActive] = useState(false)
  const [statusMessage, setStatusMessage] = useState('מוכן להפעלה')

  const isAndroid = Capacitor.getPlatform() === 'android'

  useEffect(() => {
    const refreshPermission = async () => {
      if (!isAndroid) return
      try {
        const { granted } = await Overlay.hasPermission()
        if (granted && !isActive) setStatusMessage('מוכן להפעלה')
      } catch {
        // Native plugin may not be available while previewing in a browser.
      }
    }

    refreshPermission()
    document.addEventListener('visibilitychange', refreshPermission)
    return () => document.removeEventListener('visibilitychange', refreshPermission)
  }, [isAndroid, isActive])

  const handleToggle = async () => {
    if (!isAndroid) {
      setIsActive((current) => !current)
      return
    }

    try {
      if (isActive) {
        await Overlay.stop()
        setIsActive(false)
        setStatusMessage('מוכן להפעלה')
        return
      }

      const { granted } = await Overlay.hasPermission()
      if (!granted) {
        setStatusMessage('יש לאשר הצגה מעל אפליקציות אחרות')
        await Overlay.requestPermission()
        return
      }

      setStatusMessage('ממתין לאישור Android לקליטת שמע…')
      await Overlay.start()
      await Overlay.startAudioCapture()
      setIsActive(true)
      setStatusMessage('קליטת השמע פעילה')
    } catch (error) {
      console.error(error)
      setIsActive(false)
      setStatusMessage('האישור בוטל או שלא הצלחנו להתחיל קליטת שמע')
    }
  }

  return (
    <main className="app-shell" dir="rtl">
      <section className="hero-card">
        <div className="logo-badge" aria-hidden="true">🌐</div>
        <p className="eyebrow">Live Translate</p>
        <h1>תרגום חי לכל סרטון</h1>
        <p className="subtitle">
          מזהה אוטומטית את שפת הסרטון ומציג כתוביות מתורגמות בשפה שתבחרי.
        </p>
      </section>

      <section className="settings-card" aria-label="הגדרות תרגום">
        <div className="field-group">
          <label htmlFor="source-language">שפת הסרטון</label>
          <div className="readonly-field" id="source-language">
            <span className="field-icon">✨</span>
            <div>
              <strong>זיהוי אוטומטי</strong>
              <small>האפליקציה תזהה לבד באיזו שפה מדברים</small>
            </div>
          </div>
        </div>

        <div className="field-group">
          <label htmlFor="target-language">תרגם ל־</label>
          <select
            id="target-language"
            value={targetLanguage}
            onChange={(event) => setTargetLanguage(event.target.value)}
          >
            {languages.map((language) => (
              <option key={language} value={language}>
                {language}
              </option>
            ))}
          </select>
        </div>

        <button
          type="button"
          className={`primary-button ${isActive ? 'active' : ''}`}
          onClick={handleToggle}
        >
          <span className="button-dot" />
          {isActive ? 'עצור תרגום' : 'הפעל תרגום'}
        </button>

        <div className={`status-box ${isActive ? 'active' : ''}`}>
          <span className="status-icon">{isActive ? '●' : '○'}</span>
          <div>
            <strong>{statusMessage}</strong>
            <small>
              {isActive
                ? `עברי לסרטון. כרגע אנחנו בודקים קליטת שמע פנימי; שפת היעד: ${targetLanguage}`
                : 'Android יבקש אישור לשיתוף/הקלטת המדיה בכל הפעלת תרגום חדשה'}
            </small>
          </div>
        </div>
      </section>

      <section className="how-it-works">
        <h2>שלב בדיקת השמע</h2>
        <div className="steps">
          <div className="step"><span>1</span><p>לחצי הפעל תרגום ואשרי את חלון Android</p></div>
          <div className="step"><span>2</span><p>פתחי סרטון עם קול באינסטגרם, פייסבוק או בדפדפן</p></div>
          <div className="step"><span>3</span><p>המחוון הצף יציג אם האפליקציה באמת קולטת את השמע</p></div>
        </div>
      </section>
    </main>
  )
}

export default App
