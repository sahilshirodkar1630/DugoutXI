import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { BrandMark } from '../components/BrandMark';
import { Card } from '../components/ui';

/**
 * About Dugout XI: what it is, how to play, the money rules, a downloadable guide, and who to contact.
 *
 * Open to everyone, signed in or not, so it can be linked from the sign-in page and shared before somebody has an
 * account. The text is the same as the user guide PDF (docs/user-guide/guide.html), which can be downloaded from here
 * in a Day and a Dark edition.
 */

const CONTACT = {
  name: 'Sahil Shirodkar',
  email: 'sahilshirodkar1630@gmail.com',
  phone: '9867141653',
  linkedin: 'https://www.linkedin.com/in/sahilshirodkar/',
};

export function AboutPage({ standalone = false }: { standalone?: boolean }) {
  return (
    <div className={standalone ? 'about about-standalone' : 'about'}>
      {standalone && (
        <div className="about-back">
          <Link to="/sign-in">← Sign in</Link>
        </div>
      )}

      <section className="card about-hero">
        <BrandMark size={40} />
        <h1>Dugout XI</h1>
        <p className="about-lead">
          Pick the winner of every match with your friends. The app keeps the score and the money straight.
        </p>
        <p className="note note-good" style={{ margin: 0 }}>
          <strong>No money moves through the app.</strong> You pay the organizer directly (UPI, cash, whatever your
          group uses), and the organizer pays winnings back the same way. Dugout XI records who paid, who picked what,
          who won, and who is owed what.
        </p>
      </section>

      <section className="card about-contact">
        <div>
          <h2>Built by {CONTACT.name}</h2>
          <p className="muted" style={{ margin: '2px 0 0' }}>
            Dugout XI is a free, ad-free app made for friends who love cricket. Found a problem, or have an idea? Get
            in touch.
          </p>
        </div>
        <div className="about-contact-links">
          <a className="btn btn-primary" href={`mailto:${CONTACT.email}?subject=${encodeURIComponent('Dugout XI')}`}>
            Email {CONTACT.email}
          </a>
          <a className="btn" href={`tel:+91${CONTACT.phone}`}>
            Call {CONTACT.phone}
          </a>
          <a className="btn" href={CONTACT.linkedin} target="_blank" rel="noopener noreferrer">
            LinkedIn
          </a>
        </div>
        <p className="muted small" style={{ margin: 0 }}>
          When reporting a problem, mention the league or contest, what you tapped, and what you expected to happen. A
          screenshot helps.
        </p>
      </section>

      <Card title="User guide" subtitle="Everything on this page, as a PDF to keep or share on WhatsApp.">
        <div className="row gap">
          <a className="btn btn-primary" href="/guide/Dugout-XI-User-Guide.pdf" download>
            Download guide (Day)
          </a>
          <a className="btn" href="/guide/Dugout-XI-User-Guide-Dark.pdf" download>
            Download guide (Dark)
          </a>
        </div>
        <p className="muted small" style={{ margin: 0 }}>
          The Day edition is best for printing; the Dark edition reads well on a phone.
        </p>
      </Card>

      <Card title="Quick start for players">
        <ol className="about-steps">
          <li>
            <strong>Create an account</strong> from the app link or an invite a friend sent: name, display name,
            email and a password of 8+ characters.
          </li>
          <li>
            <strong>Join</strong> a league from its invite link or code, or a one-off contest from its link or number,
            and tap <Ui primary>Ask to join</Ui>.
          </li>
          <li>
            <strong>Pay the organizer</strong> the way the league or contest says ("How to pay"), before the payment
            deadline. They confirm it and you are in.
          </li>
          <li>
            <strong>Pick the winner</strong> of each match before it starts. You can change your pick until then.
          </li>
          <li>
            <strong>Follow the table</strong> and your record; winnings are paid out by the organizer.
          </li>
        </ol>
      </Card>

      <div className="about-grid">
        <Card title="How a league works" subtitle="A season-long competition for a tournament such as IPL 2026.">
          <div className="grid-stats">
            <Figure label="Per match" value="₹100" />
            <Figure label="Matches" value="20" />
            <Figure label="Buy-in" value="₹2,000" hint="paid up front" />
            <Figure label="At the end" value="Balance" hint="handed back to you" />
          </div>
          <ul className="about-list">
            <li>Once confirmed, you are in <strong>every match automatically</strong>; you only make your picks.</li>
            <li>Each match is a small pot; those who picked the winner share it.</li>
            <li>The <strong>table</strong> ranks everyone by net profit.</li>
            <li>
              At the end the organizer hands you back your <strong>final balance = buy-in + net profit</strong>. Nobody
              pays more than the buy-in.
            </li>
            <li>Nobody can join once the first match has started.</li>
          </ul>
        </Card>

        <Card title="One-off contests" subtitle="A single match with its own entry fee, settled as soon as the result is in.">
          <ul className="about-list">
            <li>Open it from a shared link, or <strong>Contests</strong> → its number → <Ui>Open contest</Ui>.</li>
            <li>Ask to join, pay the entry when accepted, and pick before the start.</li>
            <li>After the result, the <strong>Money</strong> tab shows who is owed what; the organizer pays out.</li>
            <li>Fewer than 2 confirmed players at the start: it is cancelled and everyone is refunded.</li>
          </ul>
        </Card>
      </div>

      <Card title="Results, winnings and refunds" subtitle="The same rules for every match.">
        <div className="table-wrap keep-table">
          <table>
            <tbody>
              <tr>
                <td>The pot</td>
                <td>Entry × players. 6 players at ₹100 = ₹600.</td>
              </tr>
              <tr>
                <td>Winners share</td>
                <td>Everyone who picked the winning team gets an equal share: 2 winners get ₹300 each (+₹200).</td>
              </tr>
              <tr>
                <td>Odd paise</td>
                <td>Left over when a pot doesn't divide exactly; recorded, never handed out at random.</td>
              </tr>
              <tr>
                <td>Refund for all</td>
                <td>No result, nobody picked the winner, or everybody did.</td>
              </tr>
              <tr>
                <td>No pick</td>
                <td>Your entry is in the pot, and you can't win that match.</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="muted small" style={{ margin: 0 }}>
          A wrong result can be corrected by the organizer, with a reason kept on record, until money has been paid
          out.
        </p>
      </Card>

      <Card title="For organizers" subtitle="Whoever creates a league or contest runs it, and plays in it too.">
        <ol className="about-steps">
          <li>
            <Ui primary>Start a league</Ui> (or <Ui primary>Create a contest</Ui>): amount, matches, deadlines and how
            to pay.
          </li>
          <li>
            Share the <strong>invite link</strong> with <Ui>Share on WhatsApp</Ui>.
          </li>
          <li>
            <Ui>Accept</Ui> requests and tap <Ui>Payment received</Ui> when the money arrives.
          </li>
          <li>
            Add matches with <Ui primary>Add a contest</Ui>; every confirmed player is entered.
          </li>
          <li>
            After each match: <Ui>Work out the payouts</Ui> → <Ui primary>Confirm this result</Ui>.
          </li>
          <li>
            At the end: <Ui primary>End the league</Ui>, hand back each balance, <Ui>Mark settled</Ui>, then{' '}
            <Ui primary>Close the league</Ui>.
          </li>
        </ol>
      </Card>

      <Card title="Questions and answers">
        <div className="about-faq">
          <Faq q="Does the app take or hold my money?">
            No. All money goes directly between you and the organizer; the app only records it.
          </Faq>
          <Faq q="Can I change my pick?">Yes, as often as you like until the match starts. Then picks lock for everyone.</Faq>
          <Faq q="When can I see other people's picks?">Only once the match has started, so nobody can copy.</Faq>
          <Faq q="The match was washed out.">The organizer enters "no result" and everyone is refunded for that match.</Faq>
          <Faq q="Why is my balance different from my profit?">
            Profit counts only matches; your balance also includes the buy-in you paid. Balance = buy-in + profit.
          </Faq>
          <Faq q="I forgot my password.">
            Tap <strong>Forgot password?</strong> on the sign-in page. An admin will send you a temporary password, and
            you choose your own when you sign in.
          </Faq>
          <Faq q="I didn't get an alert.">
            Alerts appear inside the app (the bell, with a red badge). There are no emails or phone notifications.
          </Faq>
          <Faq q="The first page takes a while to load.">
            After a quiet spell the server can take up to a minute to wake. The app waits and signs you in by itself.
          </Faq>
          <Faq q="Can I keep my own record?">
            Yes: <strong>Download Excel</strong> on your profile, and on each league page.
          </Faq>
        </div>
      </Card>

      <Card title="Your data" subtitle="What the app keeps, and who sees it.">
        <ul className="about-list">
          <li>Your name, display name, email, and optionally a phone number and UPI id.</li>
          <li>
            Your <strong>phone and UPI id</strong> are seen only by you and the organizer of a league or contest
            you're in, so they can pay you.
          </li>
          <li>Passwords are stored only as a secure one-way hash; nobody, including admins, can read them.</li>
          <li>Picks, results and payments, so the scores and money add up. Changes made for someone else are logged with a reason.</li>
        </ul>
      </Card>


      <p className="muted small about-foot">Dugout XI · © 2026 {CONTACT.name}</p>
    </div>
  );
}

function Ui({ children, primary = false }: { children: ReactNode; primary?: boolean }) {
  return <span className={primary ? 'ui-chip ui-chip-primary' : 'ui-chip'}>{children}</span>;
}

function Figure({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <strong className="stat-value">{value}</strong>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  );
}

function Faq({ q, children }: { q: string; children: ReactNode }) {
  return (
    <details>
      <summary>{q}</summary>
      <p>{children}</p>
    </details>
  );
}
