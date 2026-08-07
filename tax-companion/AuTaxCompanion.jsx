import React, { useState, useMemo } from "react";

// =====================================================================
// AU TAX RETURN COMPANION — 2025-26 income year (lodged in 2026)
// Personal-use explainer & lookup tool. It does NOT calculate your
// return, store anything, or replace myTax. React state only — nothing
// is saved anywhere. No tax file number is asked for, ever.
//
// Every rate/threshold below was verified against ato.gov.au (or an
// official government mirror) in August 2026. If a figure looks wrong,
// check the source link shown next to it — rates change every 1 July.
// =====================================================================

const YEAR = "2025-26";
const LODGE_DEADLINE = "31 October 2026";

// ---- Verified ATO source links --------------------------------------
const L = {
  taxRates: "https://www.ato.gov.au/tax-rates-and-codes/tax-rates-australian-residents",
  mls: "https://www.ato.gov.au/individuals-and-families/medicare-and-private-health-insurance/medicare-levy-surcharge/medicare-levy-surcharge-income-thresholds-and-rates",
  medLevyReduction: "https://www.ato.gov.au/individuals-and-families/medicare-and-private-health-insurance/medicare-levy/medicare-levy-reduction/medicare-levy-reduction-for-low-income-earners",
  stslRates: "https://www.ato.gov.au/tax-rates-and-codes/study-and-training-support-loans-rates-and-repayment-thresholds",
  stslCompulsory: "https://www.ato.gov.au/individuals-and-families/study-and-training-support-loans/compulsory-repayments",
  stslWhatsNew: "https://www.ato.gov.au/individuals-and-families/study-and-training-support-loans/study-and-training-loans-what-s-new",
  amend: "https://www.ato.gov.au/individuals-and-families/your-tax-return/amend-your-tax-return",
  amendTimeLimits: "https://www.ato.gov.au/individuals-and-families/your-tax-return/amend-your-tax-return/time-limits-on-amendments",
  deductions: "https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/deductions-you-can-claim",
  tools: "https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/deductions-you-can-claim/work-related-deductions/tools-computers-and-items-you-use-for-work/tools-and-equipment-to-perform-your-work",
  assets300: "https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/deductions-you-can-claim/work-related-deductions/tools-computers-and-items-you-use-for-work/depreciating-assets-you-use-for-work/assets-costing-300-dollars-or-less",
  atoInterest: "https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/deductions-you-can-claim/cost-of-managing-tax-affairs/interest-charged-by-the-ato",
  myTaxDeductions: "https://www.ato.gov.au/individuals-and-families/your-tax-return/instructions-to-complete-your-tax-return/mytax-instructions/2026/deductions/claiming-deductions",
  lodgeHelp: "https://www.ato.gov.au/individuals-and-families/your-tax-return/help-and-support-to-lodge-your-tax-return",
  taxClinic: "https://www.ato.gov.au/individuals-and-families/financial-difficulties-and-disasters/support-to-lodge-and-pay/national-tax-clinic-program",
  occupationGuides: "https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/guides-for-occupations-and-industries",
  occupationGuidesAD: "https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/guides-for-occupations-and-industries/a-d",
  officeWorkersGuide: "https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/guides-for-occupations-and-industries/l-q/office-workers-income-and-work-related-deductions",
  abnLookup: "https://abr.business.gov.au",
  dgrListing: "https://abr.business.gov.au/Tools/DgrListing",
  // Standard long-lived URLs, not re-verified this build (ATO blocked
  // automated fetches) — flagged in the build notes:
  atoCalculators: "https://www.ato.gov.au/calculators-and-tools",
  atoCommunity: "https://community.ato.gov.au",
  ato: "https://www.ato.gov.au",
};

// ---- Verified figures for 2025-26 -----------------------------------
const BRACKETS = [
  // resident rates 2025-26 (excl. Medicare levy)
  { from: 0, to: 18200, rate: 0 },
  { from: 18200, to: 45000, rate: 0.16 },
  { from: 45000, to: 135000, rate: 0.3 },
  { from: 135000, to: 190000, rate: 0.37 },
  { from: 190000, to: Infinity, rate: 0.45 },
];
const WFH_RATE = 0.7; // dollars per hour, fixed rate method
const CAR_RATE = 0.88; // dollars per km, cents-per-km method
const CAR_KM_CAP = 5000; // business km per car
const MLS_TIERS = [
  { name: "Base", single: 101000, family: 202000, rate: 0 },
  { name: "Tier 1", single: 118000, family: 236000, rate: 0.01 },
  { name: "Tier 2", single: 158000, family: 316000, rate: 0.0125 },
  { name: "Tier 3", single: Infinity, family: Infinity, rate: 0.015 },
];
const MLS_CHILD_BUMP = 1500; // added to family threshold per dependent child after the first
const STSL_MIN = 67000;
const STSL_FLAT_FROM = 179286; // 10% of total repayment income from here

const money = (n) =>
  isNaN(n) ? "—" : n.toLocaleString("en-AU", { style: "currency", currency: "AUD", maximumFractionDigits: 0 });

function marginalRate(income) {
  for (const b of BRACKETS) if (income > b.from && income <= b.to) return b.rate;
  return income > 190000 ? 0.45 : 0;
}
function stslRepayment(inc) {
  if (!inc || inc <= STSL_MIN) return 0;
  if (inc >= STSL_FLAT_FROM) return 0.1 * inc;
  if (inc <= 125000) return 0.15 * (inc - STSL_MIN);
  return 8700 + 0.17 * (inc - 125000);
}

// =====================================================================
// DEDUCTIONS DATABASE — ~80 common employee expenses, 2025-26.
// Add your own entries here: copy any object and edit it.
//   claimable: "yes" | "no" | "depends"
//   partPrivate: true → the app prompts for a work-use percentage
// Seeded from the ATO's general "Deductions you can claim" pages.
// (Occupation-specific entries can be added once an occupation guide
// is chosen — see the Occupation Guides directory in the app.)
// =====================================================================
const DEDUCTIONS = [
  // ---- Car & travel ----
  { id: "commute", name: "Driving between home and work (the daily commute)", syn: ["commute", "petrol to work", "fuel", "train fare", "bus to work", "drive to work"], claimable: "no", condition: "The trip between home and your normal workplace is private, even if you live far away, work odd hours, or do small tasks on the way.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "two-jobs", name: "Travel between two separate jobs on the same day", syn: ["second job", "two jobs", "between workplaces"], claimable: "yes", condition: "", label: "Work-related car expenses (or travel expenses if not your own car)", records: "Diary of trips and km, or a 12-week logbook.", year: YEAR, url: L.deductions },
  { id: "between-sites", name: "Travel between workplaces or to clients during the work day", syn: ["client visits", "site to site", "between offices", "callouts"], claimable: "yes", condition: "", label: "Work-related car expenses", records: "Diary of trips and km, or a 12-week logbook.", year: YEAR, url: L.deductions },
  { id: "bulky-tools", name: "Driving to work while carrying bulky tools or equipment", syn: ["bulky tools", "carrying equipment", "ladder in car"], claimable: "depends", condition: "Only if your employer requires you to bring the bulky items, they are awkward to transport, AND there is no secure storage at your workplace.", label: "Work-related car expenses", records: "Evidence there is no secure storage, plus km records or logbook.", year: YEAR, url: L.deductions },
  { id: "parking-tolls", name: "Parking fees and road tolls", syn: ["parking", "tolls", "e-tag"], claimable: "depends", condition: "Yes for a deductible work trip (e.g. between offices). No for parking at or near your normal workplace for your commute.", label: "Work-related travel expenses", records: "Receipts or toll statements linked to the work trips.", year: YEAR, url: L.deductions },
  { id: "public-transport", name: "Public transport, taxi or rideshare for work trips", syn: ["uber", "taxi", "rideshare", "opal", "myki", "train", "tram"], claimable: "depends", condition: "Yes for travel in the course of work (between workplaces, to offsite meetings). No for the commute.", label: "Work-related travel expenses", records: "Receipts or transport-card statements identifying the work trips.", year: YEAR, url: L.deductions },
  { id: "overnight-travel", name: "Overnight work travel — accommodation, meals, incidentals", syn: ["hotel", "accommodation", "work trip", "per diem", "travel allowance"], claimable: "depends", condition: "Only if you travel overnight for work and pay the costs yourself. Not if you are living away from home. A travel allowance from your employer must be shown as income.", label: "Work-related travel expenses", records: "Receipts; a travel diary if away 6 or more nights in a row.", year: YEAR, url: L.deductions },
  { id: "car-running", name: "Car registration, insurance, servicing, depreciation", syn: ["rego", "car insurance", "servicing", "car repairs", "car depreciation"], claimable: "depends", condition: "Only claimable through the logbook method as a work-use percentage. The cents-per-km rate (88c) already includes ALL running costs and depreciation — you can never claim these on top of it.", label: "Work-related car expenses", records: "12-week logbook plus receipts for every cost.", year: YEAR, url: L.deductions },
  { id: "car-wash", name: "Car washing and cleaning", syn: ["car wash", "detailing"], claimable: "no", condition: "Private, even for a car used for work.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "fines", name: "Speeding or parking fines", syn: ["fine", "penalty", "infringement"], claimable: "no", condition: "Fines and penalties are never deductible, even if you got them while working.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  // ---- Clothing & laundry ----
  { id: "compulsory-uniform", name: "Compulsory work uniform", syn: ["uniform", "logo shirt", "corporate wardrobe"], claimable: "yes", condition: "It must be distinctive (e.g. employer logo) and your employer must strictly enforce wearing it.", label: "Work-related clothing, laundry and dry-cleaning expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "noncomp-uniform", name: "Non-compulsory uniform", syn: ["optional uniform"], claimable: "depends", condition: "Only if your employer has registered the design on the Register of Approved Occupational Clothing.", label: "Work-related clothing, laundry and dry-cleaning expenses", records: "Receipts; confirmation from your employer that the design is registered.", year: YEAR, url: L.deductions },
  { id: "occupation-clothing", name: "Occupation-specific clothing (e.g. chef's checked pants, scrubs)", syn: ["scrubs", "chef pants", "chef whites"], claimable: "yes", condition: "It must identify you as a member of a specific occupation and not be everyday clothing.", label: "Work-related clothing, laundry and dry-cleaning expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "protective-clothing", name: "Protective clothing and footwear", syn: ["boots", "steel cap boots", "steel-capped", "hi-vis", "high vis", "overalls", "apron", "gloves"], claimable: "yes", condition: "It must protect you from real risk of injury or illness at work (steel-capped boots, hi-vis, non-slip shoes, heavy-duty shirts).", label: "Work-related clothing, laundry and dry-cleaning expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "plain-clothes", name: "Ordinary clothes worn for work (suits, black pants, jeans)", syn: ["suit", "black pants", "office clothes", "dress code", "jeans"], claimable: "no", condition: "Conventional clothing is private even if your employer requires it and you only wear it to work.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "laundry", name: "Laundry of deductible work clothing", syn: ["washing", "laundry", "laundromat"], claimable: "depends", condition: "Only if the clothing itself is deductible (uniform / protective / occupation-specific). The ATO accepts a reasonable rate per load; total laundry claims of $150 or less don't need receipts, but you must still show how you calculated it.", label: "Work-related clothing, laundry and dry-cleaning expenses", records: "Diary of washes and calculation. Receipts if using a laundromat.", year: YEAR, url: L.deductions },
  { id: "dry-cleaning", name: "Dry-cleaning of deductible work clothing", syn: ["dry clean"], claimable: "depends", condition: "Only if the clothing itself is deductible.", label: "Work-related clothing, laundry and dry-cleaning expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "sun-protection", name: "Sunscreen, sunglasses and sun hats", syn: ["sunscreen", "sunglasses", "hat", "sunnies"], claimable: "depends", condition: "Only if your work requires you to be outdoors in the sun for extended periods. Apportion for private use.", label: "Other work-related expenses", records: "Receipts and a note of your outdoor work duties.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "glasses", name: "Prescription glasses or contact lenses", syn: ["glasses", "contacts", "spectacles"], claimable: "no", condition: "Private health cost, even if you need them to work.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "safety-glasses", name: "Safety glasses and goggles (incl. prescription safety glasses)", syn: ["goggles", "safety specs", "eye protection"], claimable: "yes", condition: "Must protect you from a real risk at work.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  // ---- Working from home ----
  { id: "wfh", name: "Working-from-home running costs", syn: ["work from home", "wfh", "home office", "remote work"], claimable: "yes", condition: "Two methods: fixed rate of 70c per hour worked from home (covers electricity, gas, internet, phone, stationery and computer consumables), or actual costs with full records. See the WFH calculator below.", label: "Working from home expenses (within work-related expenses)", records: "Fixed rate: a record of every hour worked from home for the whole year (diary, roster, timesheet) plus one bill per covered cost. Actual: all bills plus a 4-week representative diary of work use.", year: YEAR, url: L.deductions },
  { id: "internet", name: "Home internet", syn: ["internet", "wifi", "broadband", "nbn"], claimable: "depends", condition: "If you use the 70c fixed rate it is already covered — no separate claim. Under the actual cost method claim the work-use percentage.", label: "Working from home expenses", records: "Bills plus a 4-week diary of work vs private use.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "phone-plan", name: "Mobile phone plan", syn: ["phone bill", "mobile plan", "phone calls", "data"], claimable: "depends", condition: "If you use the 70c WFH fixed rate, phone usage is already covered — no separate claim. Otherwise claim the work-use percentage of your plan.", label: "Other work-related expenses", records: "Bills plus a 4-week diary or itemised account showing work use.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "phone-handset", name: "Mobile phone handset purchase", syn: ["phone", "iphone", "new phone", "handset"], claimable: "depends", condition: "Work-use percentage only. $300 or less: immediate deduction. Over $300: claim the decline in value over its effective life.", label: "Other work-related expenses", records: "Receipt plus evidence of work-use percentage.", year: YEAR, url: L.assets300, partPrivate: true },
  { id: "electricity", name: "Electricity and gas while working from home", syn: ["power bill", "electricity", "gas", "heating", "cooling"], claimable: "depends", condition: "Covered by the 70c fixed rate. Under the actual cost method you must work out the cost of the work area's usage.", label: "Working from home expenses", records: "Bills plus usage calculation.", year: YEAR, url: L.deductions },
  { id: "office-furniture", name: "Desk, office chair and furniture", syn: ["desk", "chair", "standing desk", "bookshelf"], claimable: "yes", condition: "Work-use portion. $300 or less: immediate deduction. Over $300: depreciate over effective life. Can be claimed on top of the 70c fixed rate (furniture is not a covered running cost).", label: "Other work-related expenses", records: "Receipts; work-use estimate.", year: YEAR, url: L.assets300, partPrivate: true },
  { id: "computer", name: "Computer or laptop", syn: ["laptop", "computer", "macbook", "pc"], claimable: "depends", condition: "Work-use percentage. Over $300: claim decline in value over effective life, not the whole cost at once. Claimable on top of the 70c fixed rate.", label: "Other work-related expenses", records: "Receipt, work-use diary (4 representative weeks), depreciation calculation.", year: YEAR, url: L.assets300, partPrivate: true },
  { id: "peripherals", name: "Monitor, keyboard, mouse, headset, webcam", syn: ["monitor", "keyboard", "mouse", "headset", "headphones", "webcam", "dock"], claimable: "depends", condition: "Work-use percentage; $300 rule applies per item (immediate if $300 or less and not part of a set over $300).", label: "Other work-related expenses", records: "Receipts; work-use estimate.", year: YEAR, url: L.assets300, partPrivate: true },
  { id: "printer", name: "Printer and ink", syn: ["printer", "ink", "toner", "paper"], claimable: "depends", condition: "Work-use portion. Under the fixed rate, computer consumables like ink ARE covered — no separate claim. Printer itself follows the $300 rule.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.assets300, partPrivate: true },
  { id: "stationery", name: "Stationery, diaries, notebooks", syn: ["pens", "notebook", "diary", "stationery"], claimable: "depends", condition: "Yes if bought for work — but if you use the 70c WFH fixed rate, stationery used at home is already covered.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "software", name: "Software and app subscriptions used for work", syn: ["software", "subscription", "app", "licence", "microsoft 365", "adobe"], claimable: "depends", condition: "Work-related portion of the subscription only.", label: "Other work-related expenses", records: "Receipts; note of work use.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "occupancy", name: "Rent, mortgage interest, council rates, home insurance", syn: ["rent", "mortgage", "rates", "home insurance"], claimable: "no", condition: "Employees working from home cannot claim occupancy costs. (Doing so can also affect the tax-free status of your home.)", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "wfh-snacks", name: "Coffee, tea, milk and snacks while working from home", syn: ["coffee", "tea", "snacks"], claimable: "no", condition: "Private, even if your employer supplies them at the office.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "office-cleaning", name: "Cleaning a dedicated home office", syn: ["cleaning", "home office cleaning"], claimable: "depends", condition: "Actual cost method only, and only for a dedicated work area, apportioned for any private use of the room.", label: "Working from home expenses", records: "Receipts and apportionment calculation.", year: YEAR, url: L.deductions, partPrivate: true },
  // ---- Self-education ----
  { id: "self-ed", name: "Self-education course fees related to your current job", syn: ["course", "study", "degree", "tafe", "university", "tuition"], claimable: "depends", condition: "The course must maintain or improve skills for your CURRENT job, or be likely to increase your income from it. Fees paid via a HELP loan are not deductible.", label: "Work-related self-education expenses", records: "Receipts, enrolment records, evidence of the link to your current job.", year: YEAR, url: L.deductions },
  { id: "new-career-course", name: "A course to get a new job or change careers", syn: ["career change", "new job course", "retraining"], claimable: "no", condition: "Education to open up a new income-earning activity is not deductible — it's incurred too soon.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "textbooks", name: "Textbooks and reference books", syn: ["books", "textbook", "reference"], claimable: "depends", condition: "Only if the study or the reference material relates to your current job. $300 rule applies to expensive sets.", label: "Work-related self-education expenses (or other work-related expenses)", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "conference", name: "Conferences, seminars and workshops", syn: ["conference", "seminar", "workshop", "summit"], claimable: "depends", condition: "Yes if sufficiently connected to your current work. Apportion if the event is partly a holiday.", label: "Other work-related expenses", records: "Receipts, agenda, travel records.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "prof-dev", name: "Short professional development courses", syn: ["cpd", "professional development", "training course", "first aid refresher"], claimable: "depends", condition: "Yes if required for, or clearly connected to, your current role.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "help-repayments", name: "HELP / HECS loan repayments", syn: ["hecs", "help repayment", "student loan payment"], claimable: "no", condition: "Repaying a study loan is never deductible (the loan repayments are not an expense of earning income).", label: "Not deductible", records: "—", year: YEAR, url: L.stslCompulsory },
  { id: "ssaf", name: "Student services and amenities fee (SSAF)", syn: ["ssaf", "amenities fee", "student services fee"], claimable: "depends", condition: "Only where your course itself qualifies as deductible self-education.", label: "Work-related self-education expenses", records: "Fee statement.", year: YEAR, url: L.deductions },
  { id: "course-travel", name: "Travel to attend a deductible course or conference", syn: ["travel to course", "travel to uni"], claimable: "depends", condition: "Yes if the course itself is deductible. Home-to-course travel counts here (unlike home-to-work).", label: "Work-related self-education expenses", records: "km records or fares.", year: YEAR, url: L.deductions },
  // ---- Tools & equipment ----
  { id: "tools-small", name: "Tools of trade costing $300 or less", syn: ["tools", "hand tools", "drill", "spanner"], claimable: "yes", condition: "Immediate deduction for the work-use portion, as long as the item isn't part of a set costing more than $300 in total.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.assets300, partPrivate: true },
  { id: "tools-large", name: "Tools and equipment costing more than $300", syn: ["expensive tools", "equipment", "power tools"], claimable: "yes", condition: "Claim the decline in value (depreciation) over the item's effective life, not the whole cost at once.", label: "Other work-related expenses", records: "Receipts kept for 5 years AFTER your last depreciation claim.", year: YEAR, url: L.assets300, partPrivate: true },
  { id: "tool-repairs", name: "Repairs and insurance for work tools and equipment", syn: ["tool repair", "tool insurance"], claimable: "yes", condition: "Work-use portion.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.tools, partPrivate: true },
  { id: "work-bag", name: "Work bag, briefcase or laptop bag", syn: ["bag", "briefcase", "backpack", "laptop bag", "satchel"], claimable: "depends", condition: "Only if you genuinely use it to carry work items (laptop, documents, tools). Apportion for private use; $300 rule applies.", label: "Other work-related expenses", records: "Receipt; note of what you carry.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "handbag", name: "Handbag", syn: ["handbag", "purse"], claimable: "depends", condition: "Same test as any work bag: deductible only to the extent it is genuinely used to carry work items, not for general personal use.", label: "Other work-related expenses", records: "Receipt; honest work-use estimate.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "safety-equipment", name: "Safety equipment (hard hat, ear protection, harness, masks)", syn: ["hard hat", "earmuffs", "ear plugs", "harness", "mask", "respirator", "ppe"], claimable: "yes", condition: "Must address a genuine risk in your work.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  // ---- Fees, memberships & other work costs ----
  { id: "union", name: "Union fees", syn: ["union"], claimable: "yes", condition: "", label: "Other work-related expenses", records: "Receipt or income statement showing the deduction.", year: YEAR, url: L.deductions },
  { id: "prof-assoc", name: "Professional association or membership fees", syn: ["membership", "professional body", "association", "cpa", "engineers australia"], claimable: "yes", condition: "The membership must relate to your current employment.", label: "Other work-related expenses", records: "Receipt.", year: YEAR, url: L.deductions },
  { id: "registration", name: "Renewing a practising certificate, registration or work licence", syn: ["ahpra", "practising certificate", "licence renewal", "white card renewal", "registration"], claimable: "depends", condition: "Renewals while working in the field: yes. The INITIAL certificate/licence to enter a profession: no (incurred too soon).", label: "Other work-related expenses", records: "Receipt.", year: YEAR, url: L.deductions },
  { id: "drivers-licence", name: "Driver's licence", syn: ["licence", "drivers license"], claimable: "no", condition: "Private, even if you must drive for work. (Extra fees for special vehicle classes required by your job can be different — check with the ATO.)", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "police-check", name: "Police check / Working with Children Check for a new job", syn: ["police check", "working with children", "wwcc", "background check"], claimable: "no", condition: "Costs to GET a job are incurred too soon to be deductible. A renewal required to keep your existing job can be deductible.", label: "Not deductible (initial). Renewal: other work-related expenses", records: "Receipt (for renewals).", year: YEAR, url: L.deductions },
  { id: "journals", name: "Trade journals and professional publications", syn: ["journal", "magazine", "trade publication"], claimable: "yes", condition: "Must relate to your current work.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "news-subs", name: "News and streaming subscriptions", syn: ["netflix", "news subscription", "spotify", "streaming"], claimable: "depends", condition: "Almost always no. Only deductible if there is a direct connection to your specific duties, and only the work portion.", label: "Other work-related expenses", records: "Receipts and strong evidence of the work connection.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "overtime-meals", name: "Overtime meals", syn: ["overtime meal", "meal allowance"], claimable: "depends", condition: "Only if you received an overtime meal allowance under an industrial award and it is shown in your income. No allowance = no claim.", label: "Other work-related expenses", records: "Receipts unless within the ATO's reasonable amount (still be able to show you spent it).", year: YEAR, url: L.deductions },
  { id: "lunches", name: "Everyday lunches, coffees and snacks at work", syn: ["lunch", "food", "snacks", "coffee at work"], claimable: "no", condition: "Food and drink on a normal work day is private.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "entertainment", name: "Entertaining clients (meals, drinks, events)", syn: ["client lunch", "entertainment", "drinks"], claimable: "no", condition: "Entertainment is not deductible for employees.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "gifts-colleagues", name: "Gifts for colleagues or your boss", syn: ["gift", "secret santa", "farewell gift"], claimable: "no", condition: "Private. (Different rules can apply to income earned on commission — ask the ATO.)", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "first-aid", name: "First aid course", syn: ["first aid", "cpr course"], claimable: "depends", condition: "Yes if you are a designated first aid officer or your role requires it.", label: "Other work-related expenses", records: "Receipt and evidence of the requirement.", year: YEAR, url: L.deductions },
  { id: "vaccinations", name: "Flu shots and vaccinations", syn: ["flu shot", "vaccine", "vaccination"], claimable: "no", condition: "Private medical cost, even if your employer requires it.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "covid-tests", name: "COVID-19 tests bought to attend work", syn: ["rat test", "covid test", "pcr"], claimable: "yes", condition: "Deductible if bought to determine whether you can attend or remain at work. Not if bought for private travel or convenience.", label: "Other work-related expenses", records: "Receipts.", year: YEAR, url: L.deductions },
  { id: "gym", name: "Gym membership and fitness costs", syn: ["gym", "fitness", "personal trainer"], claimable: "no", condition: "Not deductible for almost everyone. A tiny exception exists for jobs where very high fitness is an essential part of the role (e.g. some special forces / physical training instructors).", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "grooming", name: "Haircuts, grooming and makeup", syn: ["haircut", "makeup", "cosmetics", "grooming"], claimable: "no", condition: "Private, regardless of employer expectations.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "watch", name: "Watches and smart watches", syn: ["watch", "apple watch", "smart watch"], claimable: "depends", condition: "An ordinary watch is private. A smart watch can be partly deductible only if you genuinely need its functions for work — claim the work-use percentage.", label: "Other work-related expenses", records: "Receipt; work-use diary.", year: YEAR, url: L.deductions, partPrivate: true },
  { id: "income-protection", name: "Income protection insurance (policy held outside super)", syn: ["income protection", "salary continuance"], claimable: "yes", condition: "Premiums for insurance that replaces your income are deductible — but NOT if the policy is held inside your super fund.", label: "Other deductions — income protection", records: "Premium statements.", year: YEAR, url: L.deductions },
  { id: "ip-in-super", name: "Income protection held through your super fund", syn: ["insurance in super"], claimable: "no", condition: "The fund pays the premium, not you.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "life-insurance", name: "Life, trauma or TPD insurance", syn: ["life insurance", "tpd", "trauma cover"], claimable: "no", condition: "These protect you, not your income — premiums are private.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "social-club", name: "Work social club fees and party costs", syn: ["social club", "christmas party"], claimable: "no", condition: "Private.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "childcare", name: "Childcare", syn: ["childcare", "daycare", "nanny"], claimable: "no", condition: "Private, even though it lets you work. (Government childcare subsidy is separate from tax.)", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "relocation", name: "Relocation and removal costs for a job", syn: ["moving costs", "relocation", "removalist"], claimable: "no", condition: "Costs of moving for a job (even a transfer) are private.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "reimbursed", name: "Anything your employer reimbursed you for", syn: ["reimbursed", "reimbursement", "expensed"], claimable: "no", condition: "If you got the money back, you didn't bear the cost — golden rule #2.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "salary-sacrifice", name: "Items bought via salary sacrifice / salary packaging", syn: ["salary sacrifice", "salary packaging", "novated lease"], claimable: "no", condition: "These were paid from pre-tax salary — claiming them again would double-dip.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  // ---- Managing tax & money ----
  { id: "tax-agent", name: "Tax agent or accountant fees (for last year's return)", syn: ["tax agent", "accountant", "h&r block fee"], claimable: "yes", condition: "Fees paid this income year for managing your tax affairs — typically the fee for preparing last year's return.", label: "Cost of managing tax affairs", records: "Invoice/receipt.", year: YEAR, url: L.deductions },
  { id: "tax-agent-travel", name: "Travel to see your tax agent", syn: ["travel to accountant"], claimable: "yes", condition: "", label: "Cost of managing tax affairs", records: "km or fare records.", year: YEAR, url: L.deductions },
  { id: "gic", name: "ATO interest charges (general interest charge / shortfall interest charge)", syn: ["gic", "sic", "ato interest", "interest on tax debt"], claimable: "no", condition: "CHANGED from 1 July 2025: GIC and SIC incurred on or after 1 July 2025 are NO LONGER deductible, even for older debts. (Interest incurred before that date, in earlier returns, was.)", label: "Not deductible (from 2025-26)", records: "—", year: YEAR, url: L.atoInterest },
  { id: "investment-interest", name: "Interest on money borrowed to invest", syn: ["margin loan", "investment loan interest"], claimable: "depends", condition: "Deductible if the borrowed money was used to buy income-producing investments. (You've said you have no shares/rental — this likely doesn't apply.)", label: "Interest deductions", records: "Loan statements.", year: YEAR, url: L.deductions },
  { id: "bank-fees", name: "Everyday bank account fees", syn: ["bank fees", "account fees"], claimable: "no", condition: "Fees on ordinary transaction accounts are private. (Fees on an account kept solely for investment income can differ.)", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  // ---- Donations ----
  { id: "donations", name: "Donations of $2 or more to a registered charity (DGR)", syn: ["donation", "charity", "giving", "red cross", "salvos"], claimable: "depends", condition: "The organisation must be a Deductible Gift Recipient (DGR) — check it on ABN Lookup — and you must receive nothing in return.", label: "Gifts and donations", records: "Receipts (or bucket donations up to $10 total without one).", year: YEAR, url: L.dgrListing },
  { id: "raffles", name: "Raffle tickets, fundraising chocolates, charity dinners", syn: ["raffle", "fundraiser", "charity auction", "chocolate drive"], claimable: "no", condition: "You received something in return, so it isn't a gift.", label: "Not deductible", records: "—", year: YEAR, url: L.deductions },
  { id: "crowdfunding", name: "Crowdfunding for an individual (GoFundMe etc.)", syn: ["gofundme", "crowdfunding"], claimable: "no", condition: "Gifts to a person are not deductible — only gifts to endorsed DGR organisations.", label: "Not deductible", records: "—", year: YEAR, url: L.dgrListing },
  { id: "overseas-charity", name: "Donations to overseas charities without Australian DGR status", syn: ["overseas charity", "international donation"], claimable: "no", condition: "No DGR endorsement, no deduction — even for famous international charities. Check ABN Lookup for their Australian entity.", label: "Not deductible", records: "—", year: YEAR, url: L.dgrListing },
  { id: "bucket", name: "Bucket collections (small cash donations)", syn: ["bucket donation", "cash donation", "door knock"], claimable: "yes", condition: "You can claim up to $10 in total of bucket donations to DGRs without a receipt.", label: "Gifts and donations", records: "None needed up to $10 total.", year: YEAR, url: L.deductions },
  // ---- Super ----
  { id: "super", name: "Personal (after-tax) super contributions you want to deduct", syn: ["super contribution", "superannuation", "voluntary super", "notice of intent"], claimable: "depends", condition: "STOP — before claiming you MUST have given your fund a valid Notice of intent AND hold the fund's written acknowledgment. See the Super section of this app: miss the deadline and the deduction is permanently lost. A yearly contributions cap also applies — check the ATO page.", label: "Personal super contributions", records: "Notice of intent + the fund's acknowledgment letter, kept 5 years.", year: YEAR, url: L.ato },
];

// =====================================================================
// STYLES (mobile-first, big tap targets)
// =====================================================================
const S = {
  app: { fontFamily: "-apple-system, 'Segoe UI', Roboto, sans-serif", background: "#f4f6f8", minHeight: "100vh", color: "#1a2733", paddingBottom: 80 },
  header: { position: "sticky", top: 0, zIndex: 50, background: "#00437a", padding: "10px 12px 12px", boxShadow: "0 2px 6px rgba(0,0,0,.25)" },
  title: { color: "#fff", fontSize: 17, fontWeight: 700, margin: "0 0 8px 2px" },
  titleSub: { color: "#bcd6ee", fontWeight: 400, fontSize: 12, display: "block" },
  search: { width: "100%", boxSizing: "border-box", padding: "14px 16px", fontSize: 16, borderRadius: 10, border: "none", outline: "none" },
  main: { maxWidth: 640, margin: "0 auto", padding: "12px 10px" },
  card: { background: "#fff", borderRadius: 14, marginBottom: 12, boxShadow: "0 1px 3px rgba(0,0,0,.08)", overflow: "hidden" },
  cardBtn: { width: "100%", textAlign: "left", background: "none", border: "none", padding: "16px 14px", fontSize: 16, fontWeight: 700, color: "#1a2733", display: "flex", justifyContent: "space-between", alignItems: "center", cursor: "pointer", minHeight: 56 },
  cardBody: { padding: "0 14px 16px", fontSize: 15, lineHeight: 1.55 },
  num: { display: "inline-block", background: "#e3eef8", color: "#00437a", borderRadius: 8, minWidth: 26, textAlign: "center", padding: "2px 4px", marginRight: 10, fontSize: 14 },
  stamp: { display: "inline-block", background: "#e6f4e6", color: "#1d6b1d", fontSize: 11, fontWeight: 700, borderRadius: 6, padding: "2px 7px", margin: "2px 6px 2px 0", verticalAlign: "middle" },
  srcLink: { fontSize: 12, color: "#00437a" },
  warn: { background: "#fdecea", border: "1px solid #f5c6c0", borderRadius: 10, padding: "10px 12px", margin: "10px 0", fontSize: 14 },
  info: { background: "#eaf3fb", border: "1px solid #cfe3f5", borderRadius: 10, padding: "10px 12px", margin: "10px 0", fontSize: 14 },
  good: { background: "#e6f4e6", border: "1px solid #bfe3bf", borderRadius: 10, padding: "10px 12px", margin: "10px 0", fontSize: 14 },
  input: { width: "100%", boxSizing: "border-box", padding: "12px", fontSize: 16, borderRadius: 8, border: "1px solid #b9c6d0", marginTop: 4 },
  label: { fontSize: 13, fontWeight: 600, color: "#41576b", display: "block", marginTop: 10 },
  btn: { background: "#00437a", color: "#fff", border: "none", borderRadius: 10, padding: "13px 18px", fontSize: 16, fontWeight: 700, cursor: "pointer", minHeight: 48, marginTop: 12 },
  pillYes: { background: "#1d6b1d", color: "#fff", borderRadius: 6, padding: "2px 8px", fontSize: 12, fontWeight: 700 },
  pillNo: { background: "#b3261e", color: "#fff", borderRadius: 6, padding: "2px 8px", fontSize: 12, fontWeight: 700 },
  pillDep: { background: "#a05a00", color: "#fff", borderRadius: 6, padding: "2px 8px", fontSize: 12, fontWeight: 700 },
  ded: { border: "1px solid #dde5ec", borderRadius: 10, marginBottom: 8 },
  dedBtn: { width: "100%", textAlign: "left", background: "none", border: "none", padding: "13px 12px", fontSize: 15, display: "flex", gap: 8, alignItems: "flex-start", justifyContent: "space-between", cursor: "pointer", minHeight: 50 },
  check: { display: "flex", gap: 10, alignItems: "flex-start", padding: "8px 0", fontSize: 14 },
  cb: { width: 22, height: 22, flexShrink: 0, marginTop: 1 },
  result: { background: "#eaf3fb", borderRadius: 10, padding: "12px", marginTop: 12, fontSize: 15 },
  hr: { border: "none", borderTop: "1px solid #e3e9ee", margin: "14px 0" },
  small: { fontSize: 12, color: "#5b6f80" },
};

// =====================================================================
// SMALL SHARED COMPONENTS
// =====================================================================
function Src({ href, children }) {
  return (
    <span>
      <span style={S.stamp}>Current for {YEAR}</span>
      <a style={S.srcLink} href={href} target="_blank" rel="noreferrer noopener">
        {children || "ATO source"} ↗
      </a>
    </span>
  );
}

function Card({ id, title, num, open, toggle, children }) {
  return (
    <section style={S.card} id={id}>
      <button style={S.cardBtn} onClick={() => toggle(id)} aria-expanded={open}>
        <span>
          {num != null && <span style={S.num}>{num}</span>}
          {title}
        </span>
        <span style={{ color: "#7a8da0", fontSize: 20 }}>{open ? "−" : "+"}</span>
      </button>
      {open && <div style={S.cardBody}>{children}</div>}
    </section>
  );
}

function NumInput({ label, value, onChange, placeholder }) {
  return (
    <label style={S.label}>
      {label}
      <input
        style={S.input}
        type="number"
        inputMode="decimal"
        value={value}
        placeholder={placeholder || "0"}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

// =====================================================================
// DEDUCTION ENTRY
// =====================================================================
const GOLDEN_RULES = [
  "I spent the money myself",
  "I was not reimbursed by my employer or anyone else",
  "It directly relates to earning my income",
  "I have a record — receipt, invoice, bill, diary or logbook",
];

function DeductionItem({ d, openMap, setOpenMap, rulesMap, setRulesMap, pctMap, setPctMap }) {
  const open = !!openMap[d.id];
  const ticks = rulesMap[d.id] || [false, false, false, false];
  const allTicked = ticks.every(Boolean);
  const pct = pctMap[d.id] || "";
  const pill = d.claimable === "yes" ? S.pillYes : d.claimable === "no" ? S.pillNo : S.pillDep;
  const pillText = d.claimable === "yes" ? "YES" : d.claimable === "no" ? "NO" : "DEPENDS";
  return (
    <div style={S.ded}>
      <button
        style={S.dedBtn}
        onClick={() => setOpenMap((m) => ({ ...m, [d.id]: !open }))}
        aria-expanded={open}
      >
        <span style={{ flex: 1 }}>{d.name}</span>
        <span style={pill}>{pillText}</span>
      </button>
      {open && (
        <div style={{ padding: "0 12px 14px", fontSize: 14, lineHeight: 1.5 }}>
          {d.condition && (
            <p style={{ margin: "4px 0 8px" }}>
              <b>{d.claimable === "depends" ? "Only if: " : ""}</b>
              {d.condition}
            </p>
          )}
          {d.claimable !== "no" && (
            <>
              <p style={{ margin: "8px 0 2px" }}>
                <b>Where it goes in myTax:</b> {d.label}
              </p>
              <p style={{ margin: "6px 0" }}>
                <b>Records you need:</b> {d.records}
              </p>
              <div style={S.info}>
                <b>Tick all four before you claim</b> (the ATO's golden rules):
                {GOLDEN_RULES.map((r, i) => (
                  <label key={i} style={S.check}>
                    <input
                      style={S.cb}
                      type="checkbox"
                      checked={ticks[i]}
                      onChange={() =>
                        setRulesMap((m) => {
                          const next = [...(m[d.id] || [false, false, false, false])];
                          next[i] = !next[i];
                          return { ...m, [d.id]: next };
                        })
                      }
                    />
                    <span>{r}</span>
                  </label>
                ))}
                {allTicked ? (
                  <div style={{ ...S.good, marginBottom: 0 }}>All four ticked — OK to enter this one in myTax.</div>
                ) : (
                  <div style={S.small}>If you can't tick all four, don't claim it.</div>
                )}
              </div>
              {d.partPrivate && (
                <div style={S.info}>
                  <b>This one is often part-private.</b> What percentage was for work?
                  <input
                    style={S.input}
                    type="number"
                    inputMode="numeric"
                    min="0"
                    max="100"
                    placeholder="e.g. 60"
                    value={pct}
                    onChange={(e) => setPctMap((m) => ({ ...m, [d.id]: e.target.value }))}
                  />
                  {pct !== "" && (
                    <div style={{ marginTop: 6 }}>
                      Claim only <b>{pct}%</b> of the cost. Keep a 4-week diary showing how you got that number.
                    </div>
                  )}
                </div>
              )}
            </>
          )}
          <div style={{ marginTop: 8 }}>
            <Src href={d.url} />
          </div>
        </div>
      )}
    </div>
  );
}

// =====================================================================
// CALCULATORS
// =====================================================================
function DeductionValueCalc() {
  const [amount, setAmount] = useState("");
  const [income, setIncome] = useState("");
  const [medicare, setMedicare] = useState(true);
  const amt = parseFloat(amount);
  const inc = parseFloat(income);
  const mr = marginalRate(inc || 0) + (medicare && (inc || 0) > 0 ? 0.02 : 0);
  const back = amt > 0 && inc > 0 ? amt * mr : null;
  return (
    <div>
      <div style={S.warn}>
        <b>A $1,000 deduction does not put $1,000 back in your pocket.</b> It removes $1,000 from the income you're
        taxed on, so you get back roughly your marginal tax rate — often around a third.
      </div>
      <NumInput label="Deduction amount ($)" value={amount} onChange={setAmount} placeholder="1000" />
      <NumInput label="Your taxable income for the year ($)" value={income} onChange={setIncome} placeholder="85000" />
      <label style={S.check}>
        <input style={S.cb} type="checkbox" checked={medicare} onChange={() => setMedicare(!medicare)} />
        <span>Include 2% Medicare levy (most people pay it)</span>
      </label>
      {back != null && (
        <div style={S.result}>
          Marginal rate: <b>{Math.round(mr * 1000) / 10}%</b>
          <br />
          A {money(amt)} deduction is worth about <b>{money(back)}</b> to you.
        </div>
      )}
      <p style={S.small}>
        Uses the {YEAR} resident tax brackets: 0% to $18,200, then 16%, 30% (from $45,001), 37% (from $135,001), 45%
        (from $190,001). <Src href={L.taxRates} />
      </p>
    </div>
  );
}

function CarCalc() {
  const [km, setKm] = useState("");
  const [costs, setCosts] = useState("");
  const [pct, setPct] = useState("");
  const kmN = Math.min(parseFloat(km) || 0, CAR_KM_CAP);
  const centsResult = kmN * CAR_RATE;
  const logbookResult = (parseFloat(costs) || 0) * ((parseFloat(pct) || 0) / 100);
  return (
    <div>
      <p>
        Two ways to claim work-related car trips (never the commute). Pick whichever suits you — compare below.
      </p>
      <div style={S.info}>
        <b>Method 1 — Cents per kilometre.</b> {CAR_RATE * 100}c per work km, maximum {CAR_KM_CAP.toLocaleString()} km
        per car per year. The rate already covers fuel, rego, insurance, servicing AND depreciation — you cannot claim
        any of those on top. Records: how you worked out your km (diary of trips).
      </div>
      <NumInput label="Work kilometres for the year" value={km} onChange={setKm} placeholder="3200" />
      {km !== "" && (
        <div style={S.result}>
          {parseFloat(km) > CAR_KM_CAP && (
            <div>
              Capped at {CAR_KM_CAP.toLocaleString()} km.
              <br />
            </div>
          )}
          Cents-per-km claim: <b>{money(centsResult)}</b>
        </div>
      )}
      <hr style={S.hr} />
      <div style={S.info}>
        <b>Method 2 — Logbook.</b> Claim the work-use percentage of ALL car costs (fuel, rego, insurance, servicing,
        depreciation, interest). Records: a 12-week logbook (valid 5 years), odometer readings, and receipts for every
        cost.
      </div>
      <NumInput label="Total car costs for the year ($)" value={costs} onChange={setCosts} placeholder="9000" />
      <NumInput label="Work-use percentage from your logbook (%)" value={pct} onChange={setPct} placeholder="40" />
      {costs !== "" && pct !== "" && (
        <div style={S.result}>
          Logbook claim: <b>{money(logbookResult)}</b>
        </div>
      )}
      <p style={S.small}>
        <Src href={L.deductions} />
      </p>
    </div>
  );
}

function WfhCalc() {
  const [hours, setHours] = useState("");
  const [actual, setActual] = useState("");
  const [pct, setPct] = useState("");
  const fixed = (parseFloat(hours) || 0) * WFH_RATE;
  const act = (parseFloat(actual) || 0) * ((parseFloat(pct) || 0) / 100);
  return (
    <div>
      <div style={S.info}>
        <b>Method 1 — Fixed rate: {WFH_RATE * 100}c per hour worked from home.</b> Covers electricity, gas, internet,
        phone, stationery and computer consumables — you can't claim those separately. You CAN still separately claim
        depreciation on a desk, chair or computer.
        <br />
        <b>Records demanded:</b> a record of every hour worked from home for the whole year (diary, timesheet or
        roster — an estimate is not accepted), plus at least one bill for each covered cost.
      </div>
      <NumInput label="Hours worked from home this year" value={hours} onChange={setHours} placeholder="800" />
      {hours !== "" && (
        <div style={S.result}>
          Fixed-rate claim: <b>{money(fixed)}</b>
        </div>
      )}
      <hr style={S.hr} />
      <div style={S.info}>
        <b>Method 2 — Actual costs.</b> Work out the real work portion of each expense.
        <br />
        <b>Records demanded:</b> every bill and receipt, plus a 4-week representative diary showing your pattern of
        work vs private use — for each expense.
      </div>
      <NumInput label="Total actual running costs ($)" value={actual} onChange={setActual} placeholder="2400" />
      <NumInput label="Work-use percentage (%)" value={pct} onChange={setPct} placeholder="30" />
      {actual !== "" && pct !== "" && (
        <div style={S.result}>
          Actual-cost claim: <b>{money(act)}</b>
        </div>
      )}
      <p style={S.small}>
        <Src href={L.deductions} />
      </p>
    </div>
  );
}

function MlsCalc() {
  const [income, setIncome] = useState("");
  const [family, setFamily] = useState(false);
  const [kids, setKids] = useState("");
  const [coverCost, setCoverCost] = useState("");
  const inc = parseFloat(income) || 0;
  const kidsN = Math.max(0, parseInt(kids || "0", 10));
  const bump = family && kidsN > 1 ? (kidsN - 1) * MLS_CHILD_BUMP : 0;
  let tier = MLS_TIERS[0];
  for (const t of MLS_TIERS) {
    const cap = (family ? t.family : t.single) + (family ? bump : 0);
    if (inc <= cap) {
      tier = t;
      break;
    }
  }
  const surcharge = inc * tier.rate;
  const cover = parseFloat(coverCost);
  return (
    <div>
      <p>
        The Medicare levy surcharge (MLS) is an EXTRA tax (on top of the normal 2% levy) if your income is over the
        threshold and you don't hold private hospital cover.
      </p>
      <NumInput label="Your income for MLS purposes ($)" value={income} onChange={setIncome} placeholder="110000" />
      <label style={S.check}>
        <input style={S.cb} type="checkbox" checked={family} onChange={() => setFamily(!family)} />
        <span>Use family thresholds (you have a spouse and/or dependent children)</span>
      </label>
      {family && <NumInput label="Number of dependent children" value={kids} onChange={setKids} placeholder="0" />}
      {income !== "" && (
        <div style={S.result}>
          {tier.rate === 0 ? (
            <span>
              You're in the <b>base tier</b> — no surcharge applies at this income, with or without hospital cover.
            </span>
          ) : (
            <span>
              Without hospital cover you'd be in <b>{tier.name}</b>: a <b>{tier.rate * 100}%</b> surcharge ≈{" "}
              <b>{money(surcharge)}</b> per year.
            </span>
          )}
        </div>
      )}
      {tier.rate > 0 && (
        <>
          <NumInput
            label="Yearly cost of basic hospital cover you were quoted ($)"
            value={coverCost}
            onChange={setCoverCost}
            placeholder="1300"
          />
          {!isNaN(cover) && (
            <div style={cover < surcharge ? S.good : S.warn}>
              {cover < surcharge ? (
                <span>
                  Cover ({money(cover)}) costs LESS than the surcharge ({money(surcharge)}) — holding hospital cover
                  would leave you ahead, and you get the cover itself.
                </span>
              ) : (
                <span>
                  Cover ({money(cover)}) costs MORE than the surcharge ({money(surcharge)}). Paying the surcharge is
                  cheaper in pure dollars — but you get nothing for it. Your call.
                </span>
              )}
            </div>
          )}
        </>
      )}
      <p style={S.small}>
        {YEAR} thresholds — Singles: base ≤ $101,000 (0%) · Tier 1 to $118,000 (1%) · Tier 2 to $158,000 (1.25%) ·
        Tier 3 above $158,000 (1.5%). Families: double those ($202,000 / $236,000 / $316,000), plus $1,500 per
        dependent child after the first. "Income for MLS purposes" is more than taxable income — it adds reportable
        fringe benefits, reportable super contributions and net investment losses. <Src href={L.mls} />
      </p>
    </div>
  );
}

function StslCalc() {
  const [income, setIncome] = useState("");
  const inc = parseFloat(income) || 0;
  const rep = stslRepayment(inc);
  return (
    <div>
      <div style={S.info}>
        <b>New for {YEAR}:</b> study-loan repayments moved to a marginal system. You only repay when your "repayment
        income" is over ${STSL_MIN.toLocaleString()}, and only on the part above it — this is a very common reason a
        refund looks different this year.
      </div>
      <NumInput label="Your repayment income ($)" value={income} onChange={setIncome} placeholder="80000" />
      {income !== "" && (
        <div style={S.result}>
          Estimated compulsory repayment: <b>{money(rep)}</b>
          {inc > 0 && inc <= STSL_MIN && <span> — you're at or under the ${STSL_MIN.toLocaleString()} threshold.</span>}
        </div>
      )}
      <p style={S.small}>
        {YEAR} formula: nil up to $67,000 · 15c per dollar between $67,000 and $125,000 · $8,700 plus 17c per dollar
        over $125,000 · from ${STSL_FLAT_FROM.toLocaleString()}, a flat 10% of your total repayment income.
        "Repayment income" = taxable income + reportable fringe benefits + reportable super contributions + net
        investment losses + exempt foreign income. <Src href={L.stslRates} />
      </p>
    </div>
  );
}

function KeepUntilCalc() {
  const [date, setDate] = useState("");
  let until = null;
  if (date) {
    const d = new Date(date + "T00:00:00");
    if (!isNaN(d)) {
      d.setFullYear(d.getFullYear() + 5);
      until = d.toLocaleDateString("en-AU", { day: "numeric", month: "long", year: "numeric" });
    }
  }
  return (
    <div>
      <p>You must keep written evidence for 5 years from the day you lodge.</p>
      <label style={S.label}>
        The date you lodge (or lodged) this return
        <input style={S.input} type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      </label>
      {until && (
        <div style={S.result}>
          Keep every receipt for this return until at least <b>{until}</b>.
        </div>
      )}
      <div style={S.warn}>
        Keep records LONGER for: depreciating assets (5 years after your final depreciation claim) and anything
        related to capital gains tax assets (until 5 years after you sell). <Src href={L.deductions} />
      </div>
    </div>
  );
}

function WorkPctCalc() {
  const [work, setWork] = useState("");
  const [total, setTotal] = useState("");
  const w = parseFloat(work) || 0;
  const t = parseFloat(total) || 0;
  const pct = t > 0 ? Math.min(100, Math.round((w / t) * 1000) / 10) : null;
  return (
    <div>
      <p>
        For part-private items (phone, internet, laptop): track usage over a representative 4-week period, then apply
        the percentage to the whole year's cost.
      </p>
      <NumInput label="Work use in your 4-week diary (hours, calls, GB — any consistent unit)" value={work} onChange={setWork} placeholder="30" />
      <NumInput label="TOTAL use in the same period (same unit)" value={total} onChange={setTotal} placeholder="100" />
      {pct != null && work !== "" && total !== "" && (
        <div style={S.result}>
          Work-use percentage: <b>{pct}%</b>. Keep the diary — it's your evidence.
        </div>
      )}
    </div>
  );
}

// =====================================================================
// AI FALLBACK BOX
// =====================================================================
const AI_SYSTEM = `You are a lookup assistant inside a personal Australian tax return companion app. The user is an Australian employee (salary and wages only — no business, rental or shares) preparing their own ${YEAR} individual tax return in myTax.

Hard rules — never break these:
1. Answer ONLY from Australian tax rules, for the ${YEAR} income year, and state "For the ${YEAR} income year" in every answer.
2. Include a link to the most relevant ato.gov.au page in every answer.
3. NEVER state a dollar figure, rate, threshold or date except the verified figures listed below. If a question needs a figure not in the list, say so and link the ATO page where it lives.
4. If you are not sure, say "I don't know — check this ATO page" with a link, rather than guessing.
5. Do not calculate the user's tax or estimate their refund.
6. Keep answers short and plain enough for a ten-year-old.

Verified ${YEAR} figures you may state:
- Resident tax brackets: 0% to $18,200; 16% to $45,000; 30% to $135,000; 37% to $190,000; 45% above.
- Medicare levy 2%. Medicare levy surcharge thresholds: $101,000 single / $202,000 family (+$1,500 per dependent child after the first); tiers 1%, 1.25%, 1.5% (singles tier boundaries $118,000 and $158,000; families $236,000 and $316,000).
- Working-from-home fixed rate: 70 cents per hour (covers electricity, gas, internet, phone, stationery, computer consumables).
- Car cents-per-km rate: 88 cents, max 5,000 business km per car; covers all running costs and depreciation; commuting is not deductible.
- Study loans: compulsory repayment only when repayment income exceeds $67,000; 15c per dollar $67,000–$125,000; $8,700 + 17c per dollar above $125,000; flat 10% of repayment income from $179,286.
- Records: keep 5 years from lodgment. Amendment window for individuals: 2 years from the day after the notice of assessment.
- Depreciating assets: immediate deduction at $300 or less, otherwise decline in value.
- ATO interest charges (GIC/SIC) incurred on or after 1 July 2025 are not deductible.
- The $1,000 standard work deduction does NOT apply to ${YEAR}; it starts 2026-27.
- Self-lodgment deadline for this return: ${LODGE_DEADLINE}.`;

function AiBox() {
  const [q, setQ] = useState("");
  const [answer, setAnswer] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function ask() {
    if (!q.trim() || busy) return;
    setBusy(true);
    setError("");
    setAnswer("");
    try {
      const res = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "claude-sonnet-4-6",
          max_tokens: 1000,
          system: AI_SYSTEM,
          messages: [{ role: "user", content: `Income year: ${YEAR}. Question: ${q.trim()}` }],
        }),
      });
      if (!res.ok) throw new Error(`API error ${res.status}`);
      const data = await res.json();
      const text = (data.content || [])
        .filter((b) => b.type === "text")
        .map((b) => b.text)
        .join("\n");
      setAnswer(text || "No answer returned.");
    } catch (e) {
      setError("Couldn't reach the AI service (" + e.message + "). Try the ATO links above instead.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div style={S.warn}>
        <b>Warning before you rely on this:</b> the ATO has publicly cautioned that AI tax answers often draw on
        overseas or outdated tax rules — and YOU remain accountable for what goes in your return. Treat any answer
        here as a pointer, then confirm it on the linked ato.gov.au page.
      </div>
      <label style={S.label}>
        Ask a question the written content didn't cover
        <textarea
          style={{ ...S.input, minHeight: 80, resize: "vertical", fontFamily: "inherit" }}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={'e.g. "Can I claim noise-cancelling headphones I use on calls?"'}
        />
      </label>
      <button style={{ ...S.btn, opacity: busy ? 0.6 : 1 }} onClick={ask} disabled={busy}>
        {busy ? "Thinking…" : "Ask"}
      </button>
      {error && <div style={S.warn}>{error}</div>}
      {answer && <div style={{ ...S.result, whiteSpace: "pre-wrap" }}>{answer}</div>}
      <p style={S.small}>
        Your question is the only thing that leaves this page. Answers are limited to Australian rules for {YEAR},
        must link an ATO page, and may not invent figures.
      </p>
    </div>
  );
}

// =====================================================================
// SUPER — BLOCKING NOTICE-OF-INTENT CONFIRMATION
// =====================================================================
function SuperSection() {
  const [c1, setC1] = useState(false);
  const [c2, setC2] = useState(false);
  const [c3, setC3] = useState(false);
  const ok = c1 && c2 && c3;
  return (
    <div>
      <p>
        If you paid extra money into super from your own bank account (after-tax), you can usually claim it as a
        deduction — but ONLY if the paperwork below is done first. This is the one deduction where a missed step is
        permanent: no notice, no deduction, no fix later.
      </p>
      <div style={S.warn}>
        <b>Do not enter a personal super deduction in myTax until you can tick all three:</b>
        <label style={S.check}>
          <input style={S.cb} type="checkbox" checked={c1} onChange={() => setC1(!c1)} />
          <span>
            I gave my super fund a valid <b>Notice of intent to claim a deduction</b> on or before the day I lodge
            this return (or the end of the next income year, whichever comes first).
          </span>
        </label>
        <label style={S.check}>
          <input style={S.cb} type="checkbox" checked={c2} onChange={() => setC2(!c2)} />
          <span>
            My fund has sent back a written <b>acknowledgment</b> of that notice.
          </span>
        </label>
        <label style={S.check}>
          <input style={S.cb} type="checkbox" checked={c3} onChange={() => setC3(!c3)} />
          <span>
            I am holding that acknowledgment <b>right now</b>, before claiming.
          </span>
        </label>
      </div>
      {ok ? (
        <div style={S.good}>
          All three confirmed — you can enter the deduction at <b>Personal super contributions</b> in myTax. Keep the
          notice and acknowledgment with your records for 5 years.
        </div>
      ) : (
        <div style={S.warn}>
          <b>Blocked.</b> Contact your super fund and complete the notice-of-intent process first. Also: don't
          withdraw, roll over or start a pension from the fund before the notice is acknowledged — that can
          invalidate it.
        </div>
      )}
      <p>
        Two more things to know: a deduction turns your after-tax contribution into a before-tax one, so the fund
        taxes it at 15% inside super. And a yearly contributions cap applies — this app doesn't state the cap amount
        (check it on the ATO site before contributing large sums).
      </p>
      <Src href={L.ato}>ato.gov.au — search "notice of intent"</Src>
    </div>
  );
}

// =====================================================================
// MAIN APP
// =====================================================================
export default function AuTaxCompanion() {
  const [query, setQuery] = useState("");
  const [openCards, setOpenCards] = useState({ start: true });
  const [dedOpen, setDedOpen] = useState({});
  const [rulesMap, setRulesMap] = useState({});
  const [pctMap, setPctMap] = useState({});

  const toggle = (id) => setOpenCards((m) => ({ ...m, [id]: !m[id] }));

  const results = useMemo(() => {
    const s = query.trim().toLowerCase();
    if (s.length < 2) return null;
    return DEDUCTIONS.filter(
      (d) => d.name.toLowerCase().includes(s) || d.syn.some((x) => x.includes(s) || s.includes(x))
    );
  }, [query]);

  const dedProps = { openMap: dedOpen, setOpenMap: setDedOpen, rulesMap, setRulesMap, pctMap, setPctMap };

  return (
    <div style={S.app}>
      <header style={S.header}>
        <h1 style={S.title}>
          Tax Return Companion
          <span style={S.titleSub}>
            {YEAR} income year · self-lodge by {LODGE_DEADLINE} · explains, never calculates — myTax does the numbers
          </span>
        </h1>
        <input
          style={S.search}
          type="search"
          placeholder='Search "can I claim…" — try boots, laptop, uber, donation'
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </header>

      <main style={S.main}>
        {results && (
          <section style={{ ...S.card, padding: "12px 14px" }}>
            <b>
              {results.length} match{results.length === 1 ? "" : "es"} for "{query}"
            </b>
            <div style={{ marginTop: 10 }}>
              {results.length === 0 && (
                <p>
                  Nothing in the index. Try another word, check the{" "}
                  <a href={L.deductions} target="_blank" rel="noreferrer noopener">
                    ATO deductions pages
                  </a>
                  , or ask the AI box at the bottom of this page.
                </p>
              )}
              {results.map((d) => (
                <DeductionItem key={d.id} d={d} {...dedProps} />
              ))}
            </div>
          </section>
        )}

        {/* 1 ------------------------------------------------------- */}
        <Card id="start" num={1} title="Do I even need to lodge?" open={!!openCards.start} toggle={toggle}>
          <p>
            Most people with a job must lodge a tax return every year. You almost certainly need to lodge if any tax
            was taken out of your pay (look at your payslip — if there's a "tax" line, that's you), or your income was
            over the tax-free threshold ($18,200).
          </p>
          <p>
            If you earned very little and no tax was withheld, you might not need to lodge — but you can't just do
            nothing. You tell the ATO by submitting a <b>non-lodgment advice</b> ("I don't need to lodge this year")
            through your myGov account linked to the ATO, or via the ATO. That stops them chasing you for a missing
            return.
          </p>
          <p>
            Not sure? The ATO has a "Do I need to lodge" tool inside myTax and on its website — answer a few questions
            and it tells you.
          </p>
          <Src href={L.lodgeHelp}>ATO — help and support to lodge</Src>
        </Card>

        {/* 2 ------------------------------------------------------- */}
        <Card id="setup" num={2} title="Setup: myGov, and when to start" open={!!openCards.setup} toggle={toggle}>
          <p>
            myTax lives inside <b>myGov</b> (my.gov.au). One-time setup: create a myGov account, then link the
            "Australian Taxation Office" service to it. Linking asks you to prove who you are (bank account details,
            super info or a notice of assessment help here).
          </p>
          <div style={S.info}>
            <b>Don't lodge in the first week of July.</b> Wait until:
            <br />• your income statement says <b>"Tax ready"</b> (your employer has until mid-July to finalise it),
            and
            <br />• the <b>pre-fill</b> has landed — bank interest, health fund and government payment data flows into
            myTax automatically, mostly by <b>late July</b>.
          </div>
          <p>
            Lodging early with an un-finalised income statement is the classic way to get it wrong and have to amend
            later. The return is due by <b>{LODGE_DEADLINE}</b> if you lodge yourself — there's no prize for being
            first.
          </p>
          <Src href={L.lodgeHelp} />
        </Card>

        {/* 3 ------------------------------------------------------- */}
        <Card id="personalise" num={3} title="Personalise return — the tick boxes that shape everything" open={!!openCards.personalise} toggle={toggle}>
          <p>
            Early in myTax you hit a screen called <b>"Personalise return"</b>, full of tick boxes ("Salary or wages",
            "Interest", "Dividends"…). This screen decides which questions you see later. Tick too little and a whole
            income type silently disappears from your return; tick too much and you wade through screens that don't
            apply.
          </p>
          <p>For your situation (salary and wages only), expect to tick at least:</p>
          <ul>
            <li>Salary, wages or other income on an income statement/payment summary</li>
            <li>Interest (if any bank account paid you even a few cents)</li>
            <li>Deductions — tick this or you won't get the deductions screens at all</li>
          </ul>
          <p>
            Pre-filled data usually ticks the right boxes for you — check rather than untick. You can go back and
            change this screen at any time before lodging.
          </p>
          <Src href={L.myTaxDeductions}>myTax 2026 instructions</Src>
        </Card>

        {/* 4 ------------------------------------------------------- */}
        <Card id="income" num={4} title="Income checklist — miss nothing" open={!!openCards.income} toggle={toggle}>
          <p>
            The ATO already knows about most of your income (employers, banks and agencies report it directly). The
            danger isn't over-declaring — it's leaving something out, because their computers will notice.
          </p>
          <p>Tick through every source:</p>
          <ul>
            <li>
              <b>Wages</b> — every employer this year, even one you left in July. Each shows as an income statement.
            </li>
            <li>
              <b>Bank interest</b> — every account, every bank, even $3. Usually pre-filled; verify.
            </li>
            <li>
              <b>Dividends</b> — if you truly own no shares, skip. (Reinvested dividends still count as income.)
            </li>
            <li>
              <b>Government payments</b> — JobSeeker, Youth Allowance, parental leave pay etc. Pre-filled from
              Services Australia.
            </li>
            <li>
              <b>Side hustles</b> — Uber, Airtasker, OnlyFans, eBay flipping, freelancing. Gig platforms now report
              directly to the ATO, so it must go in even if no tax was withheld.
            </li>
            <li>
              <b>Cash jobs</b> — cash is still income. Declaring it is not optional.
            </li>
            <li>
              <b>Crypto</b> — selling or swapping crypto is a taxable event even without "cashing out" to dollars.
            </li>
          </ul>
          <Src href={L.ato} />
        </Card>

        {/* 5 ------------------------------------------------------- */}
        <Card id="deductions" num={5} title="Deductions — can I claim it?" open={!!openCards.deductions} toggle={toggle}>
          <p>
            A deduction is money you spent to earn your income. It comes off your taxable income — it is NOT refunded
            dollar-for-dollar (see the "what's a deduction worth" calculator below).
          </p>
          <div style={S.info}>
            <b>The golden rules — every claim must pass all of them:</b> you spent the money yourself; you weren't
            reimbursed; it directly relates to earning your income; and you have a record (receipt, invoice, bill,
            diary or logbook). Every entry below makes you tick these four before claiming.
          </div>
          <div style={S.info}>
            <b>Next year, not this year:</b> a $1,000 automatic standard work-related deduction starts from the{" "}
            <b>2026-27</b> return (applied automatically, no receipts). It does NOT apply to this {YEAR} return — and
            union/professional association fees won't reduce it when it arrives. Ignore it for now.
          </div>
          <p>
            Use the search bar at the top ("boots", "laptop", "uber"…), or browse all{" "}
            {DEDUCTIONS.length} entries:
          </p>
          <div>
            {DEDUCTIONS.map((d) => (
              <DeductionItem key={d.id} d={d} {...dedProps} />
            ))}
          </div>
          <p style={S.small}>
            Index seeded from the ATO's general deduction pages for {YEAR}. Add your occupation's guide entries via
            the Occupation Guides directory below. <Src href={L.deductions} />
          </p>
        </Card>

        {/* CALCULATORS --------------------------------------------- */}
        <Card id="calc-value" title="💰 What's this deduction actually worth?" open={openCards["calc-value"] !== false} toggle={toggle}>
          <DeductionValueCalc />
        </Card>
        <Card id="calc-car" title="🚗 Car: cents-per-km vs logbook" open={!!openCards["calc-car"]} toggle={toggle}>
          <CarCalc />
        </Card>
        <Card id="calc-wfh" title="🏠 Working from home: fixed rate vs actual" open={!!openCards["calc-wfh"]} toggle={toggle}>
          <WfhCalc />
        </Card>
        <Card id="calc-pct" title="📱 Work-use percentage helper" open={!!openCards["calc-pct"]} toggle={toggle}>
          <WorkPctCalc />
        </Card>
        <Card id="calc-keep" title="🗂️ How long do I keep receipts?" open={!!openCards["calc-keep"]} toggle={toggle}>
          <KeepUntilCalc />
        </Card>

        {/* 6 ------------------------------------------------------- */}
        <Card id="medicare" num={6} title="Medicare levy and the surcharge" open={!!openCards.medicare} toggle={toggle}>
          <p>
            Two different things with similar names:
          </p>
          <p>
            <b>Medicare levy (2%):</b> almost everyone pays this — it part-funds Medicare. myTax adds it
            automatically; it's why your refund can be less than the tax-brackets maths suggests. Low-income earners
            pay a reduced levy or none (myTax works that out —{" "}
            <a href={L.medLevyReduction} target="_blank" rel="noreferrer noopener">
              details here ↗
            </a>
            ).
          </p>
          <p>
            <b>Medicare levy surcharge (1%–1.5% extra):</b> only hits higher earners without private hospital cover.
            Use the checker below.
          </p>
          <MlsCalc />
        </Card>

        {/* 7 ------------------------------------------------------- */}
        <Card id="phi" num={7} title="Private health insurance" open={!!openCards.phi} toggle={toggle}>
          <p>
            If you hold private health cover, your fund sends your statement details straight to the ATO — they
            pre-fill into myTax (usually by late July). Check the pre-fill rather than typing from your own statement.
          </p>
          <p>
            Why it matters: the government pays a <b>rebate</b> on private health premiums, and the rebate is
            income-tested. Most people take it as reduced premiums during the year. At tax time myTax compares the
            rebate you received against what your actual income entitles you to — if you under- or over-claimed, the
            difference lands in your return (a small extra refund or bill). That's normal, not a mistake.
          </p>
          <p>
            Hospital cover is also what switches off the Medicare levy surcharge (section 6) — "extras only" cover
            does NOT count for that.
          </p>
          <p style={S.small}>
            This app deliberately doesn't state the rebate percentages — they're income-tiered and adjust each year.
            myTax applies them automatically from the pre-fill.
          </p>
          <Src href={L.ato} />
        </Card>

        {/* 8 ------------------------------------------------------- */}
        <Card id="spouse" num={8} title="Spouse details — why myTax asks about your partner" open={!!openCards.spouse} toggle={toggle}>
          <p>
            If you had a spouse at any time during the year (married OR de facto — living together as a couple
            counts), myTax requires their income details. This is not about taxing you on their money.
          </p>
          <p>It feeds three things:</p>
          <ul>
            <li>
              <b>Medicare levy surcharge</b> — with a spouse you're assessed on the FAMILY threshold ($202,000
              combined, section 6), and both incomes count.
            </li>
            <li>
              <b>Private health rebate</b> — your correct rebate tier is based on family income.
            </li>
            <li>
              <b>Offsets</b> — some tax offsets depend on spouse income (including the offset for contributing to a
              low-income spouse's super).
            </li>
          </ul>
          <p>
            You'll need their taxable income (an estimate is allowed if their return isn't done). Leaving a real
            spouse out is a common audit flag because health funds report family memberships.
          </p>
          <Src href={L.mls} />
        </Card>

        {/* 9 ------------------------------------------------------- */}
        <Card id="stsl" num={9} title="Study and training loans (HELP/HECS)" open={!!openCards.stsl} toggle={toggle}>
          <p>
            If you have a HELP/HECS (or VET, apprenticeship etc.) loan, the compulsory repayment happens INSIDE your
            tax return — myTax calculates it from your "repayment income" and adds it to your assessment. Your
            employer withheld extra during the year toward it only if you told them you have a loan.
          </p>
          <div style={S.warn}>
            <b>Didn't tell your employer about your loan?</b> Nothing was withheld for it, so the whole year's
            repayment arrives as one lump at assessment — the #1 cause of a surprise tax bill for loan holders. Fix it
            for next year: give your employer a new Withholding declaration ticking the study-loan box.
          </div>
          <StslCalc />
        </Card>

        {/* 10 ------------------------------------------------------ */}
        <Card id="super" num={10} title="Personal super contributions — read before claiming" open={!!openCards.super} toggle={toggle}>
          <SuperSection />
        </Card>

        {/* 11 ------------------------------------------------------ */}
        <Card id="why-moved" num={11} title="Why is my number different than I expected?" open={!!openCards["why-moved"]} toggle={toggle}>
          <p>The usual suspects, in rough order of how often they bite:</p>
          <ul>
            <li>
              <b>Study loan lump sum</b> — you didn't tell an employer about your HELP debt, so nothing was withheld
              for it (section 9).
            </li>
            <li>
              <b>Tax-free threshold claimed at two jobs</b> — each employer withheld as if the first $18,200 was
              tax-free, but you only get that once. Combined, too little was withheld → bill. Claim the threshold from
              one employer only (usually the higher-paying one).
            </li>
            <li>
              <b>Old debts offset against your refund</b> — the ATO (and some other agencies, like Centrelink) can
              take what you owe out of your refund before paying the rest. The notice of assessment shows this.
            </li>
            <li>
              <b>Medicare levy surcharge</b> — crossed $101,000 without hospital cover (section 6).
            </li>
            <li>
              <b>Private health rebate adjustment</b> — your income moved tiers (section 7).
            </li>
            <li>
              <b>A second income with no withholding</b> — interest, side hustles: tax on them is settled at
              assessment.
            </li>
            <li>
              <b>ATO interest no longer deductible</b> — from 1 July 2025, GIC/SIC interest on tax debts can't be
              claimed, where before it could.{" "}
              <a href={L.atoInterest} target="_blank" rel="noreferrer noopener">
                ATO page ↗
              </a>
            </li>
          </ul>
          <div style={S.info}>
            Also remember: the estimate myTax shows before you lodge is exactly that — <b>an estimate</b>. The real
            number is on your notice of assessment.
          </div>
          <Src href={L.taxRates} />
        </Card>

        {/* 12 ------------------------------------------------------ */}
        <Card id="after" num={12} title="After you lodge" open={!!openCards.after} toggle={toggle}>
          <p>
            <b>Processing:</b> most myTax returns are processed within 2 weeks, often faster. Track it in myGov → ATO
            → "Your tax return status". Chasing before 2 weeks achieves nothing.
          </p>
          <p>
            <b>Where's my refund:</b> it lands in the bank account you gave in the return, after the notice of
            assessment issues. Check the account number you entered before blaming the ATO.
          </p>
          <p>
            <b>Made a mistake?</b> Don't panic and don't lodge a second return. Use myGov → ATO → Manage tax returns →{" "}
            <b>Amend</b>. Individuals have <b>2 years</b> to amend, counted from the day after your notice of
            assessment. Out of time → you lodge an objection instead.{" "}
            <Src href={L.amendTimeLimits}>ATO — time limits on amendments</Src>
          </p>
          <div style={S.warn}>
            <b>Scam radar — read this once now:</b> the ATO will never send you an email or SMS with a link to log in,
            never threatens immediate arrest, and never asks for payment by gift cards, crypto or a "protected
            account". Refund-notification texts with links are scams — always type my.gov.au yourself. When in doubt,
            call the ATO on their published number, not one from a message.
          </div>
        </Card>

        {/* DIRECTORIES --------------------------------------------- */}
        <Card id="directories" title="📚 Directories & free help" open={!!openCards.directories} toggle={toggle}>
          <p>
            <b>ATO occupation guides</b> — what people in YOUR job can claim, with examples:
          </p>
          <ul>
            <li>
              <a href={L.occupationGuides} target="_blank" rel="noreferrer noopener">
                All occupation and industry guides ↗
              </a>{" "}
              (browse alphabetically, e.g.{" "}
              <a href={L.occupationGuidesAD} target="_blank" rel="noreferrer noopener">
                A–D ↗
              </a>
              )
            </li>
            <li>
              Example deep link:{" "}
              <a href={L.officeWorkersGuide} target="_blank" rel="noreferrer noopener">
                Office workers guide ↗
              </a>
            </li>
          </ul>
          <p>
            <b>Check a charity before donating / claiming:</b>{" "}
            <a href={L.abnLookup} target="_blank" rel="noreferrer noopener">
              ABN Lookup ↗
            </a>{" "}
            (search the charity, look for "Deductible gift recipient status"), or the{" "}
            <a href={L.dgrListing} target="_blank" rel="noreferrer noopener">
              DGR listing tool ↗
            </a>
            .
          </p>
          <p>
            <b>Free human help:</b>
          </p>
          <ul>
            <li>
              <a href={L.lodgeHelp} target="_blank" rel="noreferrer noopener">
                ATO Tax Help program ↗
              </a>{" "}
              — free, confidential help from ATO-trained volunteers if you earn about $60,000 or less with simple
              affairs (July–October).
            </li>
            <li>
              <a href={L.taxClinic} target="_blank" rel="noreferrer noopener">
                National Tax Clinic program ↗
              </a>{" "}
              — free advice through university tax clinics if you can't afford an agent.
            </li>
            <li>
              <a href={L.atoCommunity} target="_blank" rel="noreferrer noopener">
                ATO Community forum ↗
              </a>{" "}
              — ask questions, answered publicly with ATO moderation.
            </li>
          </ul>
          <p>
            <b>Official calculators:</b> the ATO's own{" "}
            <a href={L.atoCalculators} target="_blank" rel="noreferrer noopener">
              calculators and tools ↗
            </a>{" "}
            (tax withheld, super, decline in value and more) — use those for anything this app doesn't cover.
          </p>
        </Card>

        {/* AI ------------------------------------------------------ */}
        <Card id="ai" title="🤖 Ask a question (AI fallback)" open={!!openCards.ai} toggle={toggle}>
          <AiBox />
        </Card>

        <p style={{ ...S.small, textAlign: "center", padding: "8px 16px" }}>
          Personal-use explainer for the {YEAR} return. Nothing is stored; no TFN is ever asked for. Content adapted
          from ato.gov.au (© Australian Taxation Office) — this app is not affiliated with or endorsed by the ATO.
          Always confirm on the linked ATO pages before lodging.
        </p>
      </main>
    </div>
  );
}
