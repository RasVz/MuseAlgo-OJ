// Registration uses the same default as user.rating and contest settlement (1200).
export const INITIAL_CONTEST_RATING = 1200

export function createRatingTimeline(history = [], registeredAt = null) {
  return [
    {
      kind: 'registration',
      contestName: '注册起点',
      rating: INITIAL_CONTEST_RATING,
      ratingChange: 0,
      contestId: null,
      rank: null,
      date: registeredAt || null,
    },
    ...history.map((item) => ({ ...item, kind: 'contest' })),
  ]
}
