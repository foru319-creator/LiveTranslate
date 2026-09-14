import { useState } from 'react'
import './App.css'

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
          onClick={() => setIsActive((current) => !current)}
        >
          <span className="button-dot" />
          {isActive ? 'עצור תרגום' : 'הפעל תרגום'}
        </button>

        <div className={`status-box ${isActive ? 'active' : ''}`}>
          <span className="status-icon">{isActive ? '●' : '○'}</span>
          <div>
            <strong>{isActive ? 'התרגום פעיל' : 'מוכן להפעלה'}</strong>
            <small>
              {isActive
                ? `כתוביות יתורגמו ל־${targetLanguage}`
                : 'בהמשך הכפתור הצף יעבוד מעל Instagram, Facebook והדפדפן'}
            </small>
          </div>
        </div>
      </section>

      <section className="how-it-works">
        <h2>איך זה יעבוד?</h2>
        <div className="steps">
          <div className="step"><span>1</span><p>פותחים סרטון</p></div>
          <div className="step"><span>2</span><p>לוחצים על הכפתור הצף</p></div>
          <div className="step"><span>3</span><p>מקבלים כתוביות מתורגמות</p></div>
        </div>
      </section>
    </main>
  )
}

export default App
