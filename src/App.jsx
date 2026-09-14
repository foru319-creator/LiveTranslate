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
        if (granted) setStatusMessage('מוכן להפעלה')
      } catch {
        // Native plugin may not be available while previewing in a browser.
      }
    }

    refreshPermission()
    document.addEventListener('visibilitychange', refreshPermission)
    return () => document.removeEventListener('visibilitychange', refreshPermission)
  }, [isAndroid])

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

      await Overlay.start()
      setIsActive(true)
      setStatusMessage('הכפתור הצף פעיל')
    } catch (error) {
      console.error(error)
      setStatusMessage('לא הצלחנו להפעיל את הכפתור הצף')
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
          {isActive ? 'עצור כפתור צף' : 'הפעל כפתור צף'}
        </button>

        <div className={`status-box ${isActive ? 'active' : ''}`}>
          <span className="status-icon">{isActive ? '●' : '○'}</span>
          <div>
            <strong>{statusMessage}</strong>
            <small>
              {isActive
                ? `עברי לאינסטגרם, פייסבוק או לדפדפן. היעד שנבחר: ${targetLanguage}`
                : 'בהפעלה הראשונה Android יבקש הרשאה להצגת הכפתור מעל אפליקציות אחרות'}
            </small>
          </div>
        </div>
      </section>

      <section className="how-it-works">
        <h2>בדיקת הכפתור הצף</h2>
        <div className="steps">
          <div className="step"><span>1</span><p>הפעילי את הכפתור</p></div>
          <div className="step"><span>2</span><p>אשרי הצגה מעל אפליקציות</p></div>
          <div className="step"><span>3</span><p>צאי מהאפליקציה וגררי את 🌐 על המסך</p></div>
        </div>
      </section>
    </main>
  )
}

export default App
